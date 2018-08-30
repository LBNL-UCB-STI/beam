package beam.agentsim.agents

import akka.actor.FSM.Failure
import akka.actor.{ActorRef, FSM, Props, Stash}
import beam.agentsim.Resource.{
  CheckInResource,
  NotifyResourceIdle,
  NotifyResourceInUse,
  RegisterResource
}

import beam.agentsim.ResourceManager.NotifyVehicleResourceIdle
import beam.agentsim.agents.BeamAgent._
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.agents.household.HouseholdActor.ReleaseVehicleReservation
import beam.agentsim.agents.modalbehaviors.ChoosesMode.ChoosesModeData
import beam.agentsim.agents.modalbehaviors.DrivesVehicle.{
  NotifyLegEndTrigger,
  NotifyLegStartTrigger,
  StartLegTrigger
}
import beam.agentsim.agents.modalbehaviors.{ChoosesMode, DrivesVehicle, ModeChoiceCalculator}
import beam.agentsim.agents.parking.ChoosesParking
import beam.agentsim.agents.parking.ChoosesParking.{ChoosingParkingSpot, ReleasingParkingSpot}
import beam.agentsim.agents.planning.{BeamPlan, Tour}
import beam.agentsim.agents.ridehail.{ReserveRide, RideHailRequest, RideHailResponse}
import beam.agentsim.agents.vehicles._
import beam.agentsim.events.{ReplanningEvent, ReserveRideHailEvent}
import beam.agentsim.scheduler.BeamAgentScheduler.{
  CompletionNotice,
  IllegalTriggerGoToError,
  ScheduleTrigger
}
import beam.agentsim.scheduler.Trigger
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{CAR, DRIVE_TRANSIT, NONE, WALK, WALK_TRANSIT}
import beam.router.RoutingModel._
import beam.sim.BeamServices
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events._
import org.matsim.api.core.v01.population._
import org.matsim.core.api.experimental.events.{EventsManager, TeleportationArrivalEvent}
import org.matsim.households.Household
import org.matsim.vehicles.Vehicle

import scala.concurrent.duration._

/**
  */
object PersonAgent {

  val timeToChooseMode: Double = 0.0
  val minActDuration: Double = 0.0
  val teleportWalkDuration = 0.0

  def props(
    scheduler: ActorRef,
    services: BeamServices,
    modeChoiceCalculator: ModeChoiceCalculator,
    transportNetwork: TransportNetwork,
    router: ActorRef,
    rideHailManager: ActorRef,
    parkingManager: ActorRef,
    eventsManager: EventsManager,
    personId: Id[PersonAgent],
    household: Household,
    plan: Plan,
    humanBodyVehicleId: Id[Vehicle]
  ): Props = {
    Props(
      new PersonAgent(
        scheduler,
        services,
        modeChoiceCalculator,
        transportNetwork,
        router,
        rideHailManager,
        eventsManager,
        personId,
        plan,
        humanBodyVehicleId,
        parkingManager
      )
    )
  }

  trait PersonData extends DrivingData

  trait DrivingData {
    def currentVehicle: VehicleStack

    def passengerSchedule: PassengerSchedule

    def currentLegPassengerScheduleIndex: Int

    def withPassengerSchedule(newPassengerSchedule: PassengerSchedule): DrivingData

    def withCurrentLegPassengerScheduleIndex(currentLegPassengerScheduleIndex: Int): DrivingData
    def hasParkingBehaviors: Boolean
  }

  case class LiterallyDrivingData(delegate: DrivingData, legEndsAt: Double) extends DrivingData { // sorry
    def currentVehicle: VehicleStack = delegate.currentVehicle

    def passengerSchedule: PassengerSchedule = delegate.passengerSchedule

    def currentLegPassengerScheduleIndex: Int =
      delegate.currentLegPassengerScheduleIndex

    def withPassengerSchedule(newPassengerSchedule: PassengerSchedule): DrivingData =
      LiterallyDrivingData(delegate.withPassengerSchedule(newPassengerSchedule), legEndsAt)

    def withCurrentLegPassengerScheduleIndex(currentLegPassengerScheduleIndex: Int) =
      LiterallyDrivingData(
        delegate.withCurrentLegPassengerScheduleIndex(currentLegPassengerScheduleIndex),
        legEndsAt
      )
    override def hasParkingBehaviors: Boolean = false
  }

  type VehicleStack = Vector[Id[Vehicle]]

  case class BasePersonData(
    currentActivityIndex: Int = 0,
    currentTrip: Option[EmbodiedBeamTrip] = None,
    restOfCurrentTrip: List[EmbodiedBeamLeg] = List(),
    currentVehicle: VehicleStack = Vector(),
    currentTourMode: Option[BeamMode] = None,
    currentTourPersonalVehicle: Option[Id[Vehicle]] = None,
    passengerSchedule: PassengerSchedule = PassengerSchedule(),
    currentLegPassengerScheduleIndex: Int = 0,
    hasDeparted: Boolean = false
  ) extends PersonData {
    override def withPassengerSchedule(newPassengerSchedule: PassengerSchedule): DrivingData =
      copy(passengerSchedule = newPassengerSchedule)

    override def withCurrentLegPassengerScheduleIndex(
      currentLegPassengerScheduleIndex: Int
    ): DrivingData = copy(currentLegPassengerScheduleIndex = currentLegPassengerScheduleIndex)

    override def hasParkingBehaviors: Boolean = true
  }

  case object PerformingActivity extends BeamAgentState

  sealed trait Traveling extends BeamAgentState

  case object ChoosingMode extends Traveling

  case object WaitingForDeparture extends Traveling

  case object WaitingForReservationConfirmation extends Traveling

  case object Waiting extends Traveling

  case object ProcessingNextLegOrStartActivity extends Traveling

  case object WaitingToDrive extends Traveling

  case object WaitingToDriveInterrupted extends Traveling

  case object PassengerScheduleEmpty extends Traveling

  case object PassengerScheduleEmptyInterrupted extends Traveling

  case object ReadyToChooseParking extends Traveling

  case object Moving extends Traveling

  case object Driving extends Traveling

  case object DrivingInterrupted extends Traveling

  case class ActivityStartTrigger(tick: Double) extends Trigger

  case class ActivityEndTrigger(tick: Double) extends Trigger

  case class PersonDepartureTrigger(tick: Double) extends Trigger

}

class PersonAgent(
  val scheduler: ActorRef,
  val beamServices: BeamServices,
  val modeChoiceCalculator: ModeChoiceCalculator,
  val transportNetwork: TransportNetwork,
  val router: ActorRef,
  val rideHailManager: ActorRef,
  val eventsManager: EventsManager,
  override val id: Id[PersonAgent],
  val matsimPlan: Plan,
  val bodyId: Id[Vehicle],
  val parkingManager: ActorRef
) extends DrivesVehicle[PersonData]
    with ChoosesMode
    with ChoosesParking
    with Stash {

  override def logDepth: Int = beamServices.beamConfig.beam.debug.actor.logDepth

  val _experiencedBeamPlan: BeamPlan = BeamPlan(matsimPlan)

  def scaleTimeByValueOfTime(time: BigDecimal, beamMode: Option[BeamMode] = None): BigDecimal =
    modeChoiceCalculator.scaleTimeByVot(time, beamMode)

  startWith(Uninitialized, BasePersonData())

  def activityOrMessage(ind: Int, msg: String): Either[String, Activity] = {
    if (ind < 0 || ind >= _experiencedBeamPlan.activities.length) Left(msg)
    else Right(_experiencedBeamPlan.activities(ind))
  }

  def currentActivity(data: BasePersonData): Activity =
    _experiencedBeamPlan.activities(data.currentActivityIndex)

  def nextActivity(data: BasePersonData): Either[String, Activity] = {
    activityOrMessage(data.currentActivityIndex + 1, "plan finished")
  }

  def currentTour(data: BasePersonData): Tour = {
    stateName match {
      case PerformingActivity =>
        _experiencedBeamPlan.getTourContaining(currentActivity(data))
      case _ =>
        _experiencedBeamPlan.getTourContaining(nextActivity(data).right.get)
    }
  }

  when(Uninitialized) {
    case Event(TriggerWithId(InitializeTrigger(_), triggerId), _) =>
      goto(Initialized) replying CompletionNotice(
        triggerId,
        Vector(ScheduleTrigger(ActivityStartTrigger(0.0), self))
      )
  }

  when(Initialized) {
    case Event(TriggerWithId(ActivityStartTrigger(tick), triggerId), data: BasePersonData) =>
      logDebug(s"starting at ${currentActivity(data).getType} @ $tick")
      goto(PerformingActivity) replying CompletionNotice(
        triggerId,
        Vector(ScheduleTrigger(ActivityEndTrigger(currentActivity(data).getEndTime), self))
      )
  }

  when(PerformingActivity) {
    case Event(TriggerWithId(ActivityEndTrigger(tick), triggerId), data: BasePersonData) =>
      nextActivity(data).fold(
        msg => {
          logDebug(s"didn't get nextActivity because $msg")
          stop replying CompletionNotice(triggerId)
        },
        nextAct => {
          logDebug(s"wants to go to ${nextAct.getType} @ $tick")
          holdTickAndTriggerId(tick, triggerId)
          goto(ChoosingMode) using ChoosesModeData(
            personData = data.copy(
              // If we don't have a current tour mode (i.e. are not on a tour aka at home),
              // use the mode of the next leg as the new tour mode.
              currentTourMode = data.currentTourMode.orElse(
                _experiencedBeamPlan.getPlanElements
                  .get(_experiencedBeamPlan.getPlanElements.indexOf(nextAct) - 1) match {
                  case leg: Leg =>
                    BeamMode.fromString(leg.getMode) match {
                      case NONE =>
                        None
                      case anyOther: BeamMode =>
                        Some(anyOther)
                    }
                  case _ => None
                }
              )
            )
          )
        }
      )
  }

  when(WaitingForDeparture) {

    /**
      * Callback from [[ChoosesMode]]
     **/
    case Event(
        TriggerWithId(PersonDepartureTrigger(tick), triggerId),
        data @ BasePersonData(_, Some(currentTrip), _, _, _, _, _, _, false)
        ) =>
      // We end our activity when we actually leave, not when we decide to leave, i.e. when we look for a bus or
      // hail a ride. We stay at the party until our Uber is there.
      eventsManager.processEvent(
        new ActivityEndEvent(
          tick,
          id,
          currentActivity(data).getLinkId,
          currentActivity(data).getFacilityId,
          currentActivity(data).getType
        )
      )
      assert(currentActivity(data).getLinkId != null)
      eventsManager.processEvent(
        new PersonDepartureEvent(
          tick,
          id,
          currentActivity(data).getLinkId,
          currentTrip.tripClassifier.value
        )
      )
      holdTickAndTriggerId(tick, triggerId)
      goto(ProcessingNextLegOrStartActivity) using data.copy(hasDeparted = true)

    case Event(
        TriggerWithId(PersonDepartureTrigger(tick), triggerId),
        BasePersonData(_, _, restOfCurrentTrip, _, _, _, _, _, true)
        ) =>
      // We're coming back from replanning, i.e. we are already on the trip, so we don't throw a departure event
      logDebug(s"replanned to leg ${restOfCurrentTrip.head}")
      holdTickAndTriggerId(tick, triggerId)
      goto(ProcessingNextLegOrStartActivity)
  }

  def handleSuccessfulReservation(
    triggersToSchedule: Vector[ScheduleTrigger],
    data: BasePersonData
  ): FSM.State[BeamAgentState, PersonData] = {
    val (_, triggerId) = releaseTickAndTriggerId()
    log.debug("scheduling triggers from reservation responses: {}", triggersToSchedule)
    scheduler ! CompletionNotice(triggerId, triggersToSchedule)
    goto(Waiting) using data

  }

  when(WaitingForReservationConfirmation) {
    case Event(ReservationResponse(_, Right(response), _), data: BasePersonData) =>
      handleSuccessfulReservation(response.triggersToSchedule, data)
    case Event(ReservationResponse(_, Left(firstErrorResponse), _), data: BasePersonData) =>
      logDebug(s"replanning because ${firstErrorResponse.errorCode}")
      val (tick, triggerId) = releaseTickAndTriggerId()
      eventsManager.processEvent(new ReplanningEvent(tick, Id.createPersonId(id)))
      holdTickAndTriggerId(tick, triggerId)
      goto(ChoosingMode) using ChoosesModeData(data, isWithinTripReplanning = true)
    case Event(RideHailResponse(_, _, None, triggersToSchedule), data: BasePersonData) =>
      handleSuccessfulReservation(triggersToSchedule, data)
    case Event(RideHailResponse(_, _, Some(error), _), data: BasePersonData) =>
      logDebug(s"replanning because ${error.errorCode}")
      val (tick, triggerId) = releaseTickAndTriggerId()
      eventsManager.processEvent(new ReplanningEvent(tick, Id.createPersonId(id)))
      holdTickAndTriggerId(tick, triggerId)
      goto(ChoosingMode) using ChoosesModeData(
        data.copy(currentTourMode = None),
        isWithinTripReplanning = true
      )
  }

  when(Waiting) {
    /*
     * Learn as passenger that leg is starting
     */

    case Event(
        TriggerWithId(NotifyLegStartTrigger(_, _, _), triggerId),
        BasePersonData(_, _, currentLeg :: _, currentVehicle, _, _, _, _, _)
        ) if currentLeg.beamVehicleId == currentVehicle.head =>
      logDebug(s"Already on vehicle: ${currentVehicle.head}")
      goto(Moving) replying CompletionNotice(triggerId)

    case Event(
        TriggerWithId(NotifyLegStartTrigger(tick, _, vehicleToEnter), triggerId),
        data @ BasePersonData(_, _, _ :: _, currentVehicle, _, _, _, _, _)
        ) =>
      logDebug(s"PersonEntersVehicle: $vehicleToEnter")
      eventsManager.processEvent(new PersonEntersVehicleEvent(tick, id, vehicleToEnter))
      goto(Moving) replying CompletionNotice(triggerId) using data.copy(
        currentVehicle = vehicleToEnter +: currentVehicle
      )
  }

  when(Moving) {
    /*
     * Learn as passenger that leg is ending
     */
    case Event(
        TriggerWithId(NotifyLegEndTrigger(_, _, _), triggerId),
        data @ BasePersonData(_, _, _ :: restOfCurrentTrip, currentVehicle, _, _, _, _, _)
        ) if restOfCurrentTrip.head.beamVehicleId == currentVehicle.head =>
      // The next vehicle is the same as current so just update state and go to Waiting
      goto(Waiting) replying CompletionNotice(triggerId) using data.copy(
        restOfCurrentTrip = restOfCurrentTrip
      )

    case Event(
        TriggerWithId(NotifyLegEndTrigger(tick, _, vehicleToExit), triggerId),
        data @ BasePersonData(_, _, _ :: restOfCurrentTrip, currentVehicle, _, _, _, _, _)
        ) =>
      // The next vehicle is different from current so we exit the current vehicle
      eventsManager.processEvent(new PersonLeavesVehicleEvent(tick, id, vehicleToExit))
      holdTickAndTriggerId(tick, triggerId)
      goto(ProcessingNextLegOrStartActivity) using data.copy(
        restOfCurrentTrip = restOfCurrentTrip,
        currentVehicle = currentVehicle.tail
      )

  }

  // Callback from DrivesVehicle. Analogous to NotifyLegEndTrigger, but when driving ourselves.
  when(PassengerScheduleEmpty) {
    case Event(PassengerScheduleEmptyMessage(_), data: BasePersonData) =>
      val (tick, triggerId) = releaseTickAndTriggerId()
      if (data.restOfCurrentTrip.head.unbecomeDriverOnCompletion) {
        beamServices.vehicles(data.currentVehicle.head).unsetDriver()
        eventsManager.processEvent(
          new PersonLeavesVehicleEvent(tick, Id.createPersonId(id), data.currentVehicle.head)
        )
      }
      holdTickAndTriggerId(tick, triggerId)
      goto(ProcessingNextLegOrStartActivity) using data.copy(
        restOfCurrentTrip = data.restOfCurrentTrip.tail,
        currentVehicle = if (data.restOfCurrentTrip.head.unbecomeDriverOnCompletion) {
          data.currentVehicle.tail
        } else {
          data.currentVehicle
        }
      )
  }

  when(ReadyToChooseParking, stateTimeout = Duration.Zero) {
    case Event(
        StateTimeout,
        data @ BasePersonData(_, _, currentLeg :: theRestOfCurrentTrip, _, _, _, _, _, _)
        ) =>
      log.debug(theRestOfCurrentTrip.toString())
      goto(ChoosingParkingSpot) using data.copy(restOfCurrentTrip = theRestOfCurrentTrip)
  }

  onTransition {
    case _ -> _ =>
      unstashAll()
  }

  /**
    * processNextLegOrStartActivity
    *
    * This should be called when it's time to either embark on another leg in a trip or to wrap up a trip that is
    * now complete. There are four outcomes possible:
    *
    * 1 There are more legs in the trip and the [[PersonAgent]] is the driver => goto [[WaitingToDrive]] and schedule
    * [[StartLegTrigger]]
    * 2 There are more legs in the trip but the [[PersonAgent]] is a passenger => goto [[Waiting]] and schedule nothing
    * further (the driver will initiate the start of the leg)
    * 3 The trip is over and there are more activities in the agent plan => goto [[PerformingActivity]] and schedule end
    * of activity
    * 4 The trip is over and there are no more activities in the agent plan => goto [[Finish]]
   **/
  when(ProcessingNextLegOrStartActivity, stateTimeout = Duration.Zero) {
    case Event(
        StateTimeout,
        data @ BasePersonData(_, _, nextLeg :: restOfCurrentTrip, currentVehicle, _, _, _, _, _)
        ) if nextLeg.asDriver =>
      val legsToInclude = nextLeg +: restOfCurrentTrip.takeWhile(
        _.beamVehicleId == nextLeg.beamVehicleId
      )

      scheduler ! CompletionNotice(
        _currentTriggerId.get,
        Vector(ScheduleTrigger(StartLegTrigger(_currentTick.get, nextLeg.beamLeg), self))
      )

      val (stateToGo, currentTick) =
        if (nextLeg.asDriver && nextLeg.beamLeg.mode == CAR) {
          (ReleasingParkingSpot, _currentTick.get)
        } else {
          val (currentTick, _) = releaseTickAndTriggerId()
          (WaitingToDrive, currentTick)
        }

      val theNewVehicle = beamServices.vehicles(nextLeg.beamVehicleId)
      if (theNewVehicle.driver.isDefined && self != theNewVehicle.driver.get) {
        stop(
          Failure(
            s"person $id tried to drive vehicle ${theNewVehicle.id} but already occupied by driver ${theNewVehicle.driver.get}"
          )
        )
      } else {
        //      goto(WaitingToDrive) using data.copy(
        goto(stateToGo) using data.copy(
          passengerSchedule = PassengerSchedule().addLegs(legsToInclude.map(_.beamLeg)),
          currentLegPassengerScheduleIndex = 0,
          currentVehicle =
            if (currentVehicle.isEmpty || currentVehicle.head != nextLeg.beamVehicleId) {
              theNewVehicle
                .becomeDriver(self)
                .fold(
                  fa =>
                    stop(
                      Failure(
                        "shouldn't be possible to execut this code but just in case this is becomeDriver failure"
                      )
                  ),
                  fb => {
                    eventsManager.processEvent(
                      new PersonEntersVehicleEvent(
                        currentTick,
                        Id.createPersonId(id),
                        nextLeg.beamVehicleId
                      )
                    )
                  }
                )
              nextLeg.beamVehicleId +: currentVehicle
            } else {
              currentVehicle
            }
        )
      }
    case Event(StateTimeout, data @ BasePersonData(_, _, nextLeg :: _, _, _, _, _, _, _))
        if nextLeg.beamLeg.startTime < _currentTick.get =>
      // We've missed the bus. This occurs when the actual ride hail trip takes much longer than planned (based on the
      // initial inquiry). So we replan but change tour mode to WALK_TRANSIT since we've already done our ride hail portion.
      logWarn(
        s"Missed transit pickup during a ride_hail_transit trip, late by ${_currentTick.get - nextLeg.beamLeg.startTime} sec"
      )
      goto(ChoosingMode) using ChoosesModeData(
        personData = data.copy(currentTourMode = Some(WALK_TRANSIT))
      )
    case Event(StateTimeout, BasePersonData(_, _, nextLeg :: tailOfCurrentTrip, _, _, _, _, _, _))
        if nextLeg.beamLeg.mode.isTransit =>
      val legSegment = nextLeg :: tailOfCurrentTrip.takeWhile(
        leg => leg.beamVehicleId == nextLeg.beamVehicleId
      )
      val resRequest = ReservationRequest(
        legSegment.head.beamLeg,
        legSegment.last.beamLeg,
        VehiclePersonId(legSegment.head.beamVehicleId, id)
      )
      TransitDriverAgent.selectByVehicleId(legSegment.head.beamVehicleId) ! resRequest
      goto(WaitingForReservationConfirmation)
    case Event(StateTimeout, BasePersonData(_, _, nextLeg :: tailOfCurrentTrip, _, _, _, _, _, _))
        if nextLeg.isRideHail =>
      val legSegment = nextLeg :: tailOfCurrentTrip.takeWhile(
        leg => leg.beamVehicleId == nextLeg.beamVehicleId
      )
      val departAt = DiscreteTime(legSegment.head.beamLeg.startTime.toInt)

      rideHailManager ! RideHailRequest(
        ReserveRide,
        VehiclePersonId(bodyId, id, Some(self)),
        beamServices.geo.wgs2Utm(nextLeg.beamLeg.travelPath.startPoint.loc),
        departAt,
        beamServices.geo.wgs2Utm(legSegment.last.beamLeg.travelPath.endPoint.loc)
      )

      eventsManager.processEvent(
        new ReserveRideHailEvent(
          _currentTick.getOrElse(departAt.atTime),
          id,
          departAt.atTime,
          nextLeg.beamLeg.travelPath.startPoint.loc,
          legSegment.last.beamLeg.travelPath.endPoint.loc
        )
      )

      goto(WaitingForReservationConfirmation)
    case Event(StateTimeout, BasePersonData(_, _, _ :: _, _, _, _, _, _, _)) =>
      val (_, triggerId) = releaseTickAndTriggerId()
      scheduler ! CompletionNotice(triggerId)
      goto(Waiting)

    case Event(
        StateTimeout,
        data @ BasePersonData(
          currentActivityIndex,
          Some(currentTrip),
          _,
          _,
          currentTourMode,
          currentTourPersonalVehicle,
          _,
          _,
          _
        )
        ) =>
      nextActivity(data) match {
        case Right(activity) =>
          val (tick, triggerId) = releaseTickAndTriggerId()
          val endTime =
            if (activity.getEndTime >= tick && Math
                  .abs(activity.getEndTime) < Double.PositiveInfinity) {
              activity.getEndTime
            } else if (activity.getEndTime >= 0.0 && activity.getEndTime < tick) {
              tick
            } else {
              //            logWarn(s"Activity endTime is negative or infinite ${activity}, assuming duration of 10
              // minutes.")
              //TODO consider ending the day here to match MATSim convention for start/end activity
              tick + 60 * 10
            }
          // Report travelled distance for inclusion in experienced plans.
          // We currently get large unaccountable differences in round trips, e.g. work -> home may
          // be twice as long as home -> work. Probably due to long links, and the location of the activity
          // on the link being undefined.
          eventsManager.processEvent(
            new TeleportationArrivalEvent(
              tick,
              id,
              currentTrip.legs.map(l => l.beamLeg.travelPath.distanceInM).sum
            )
          )
          assert(activity.getLinkId != null)
          eventsManager.processEvent(
            new PersonArrivalEvent(tick, id, activity.getLinkId, currentTrip.tripClassifier.value)
          )
          eventsManager.processEvent(
            new ActivityStartEvent(
              tick,
              id,
              activity.getLinkId,
              activity.getFacilityId,
              activity.getType
            )
          )
          scheduler ! CompletionNotice(
            triggerId,
            Vector(ScheduleTrigger(ActivityEndTrigger(endTime), self))
          )
          goto(PerformingActivity) using data.copy(
            currentActivityIndex = currentActivityIndex + 1,
            currentTrip = None,
            restOfCurrentTrip = List(),
            currentTourPersonalVehicle = currentTourPersonalVehicle match {
              case Some(personalVeh) =>
                if (activity.getType.equals("Home")) {
                  context.parent ! ReleaseVehicleReservation(id, personalVeh)
                  context.parent ! CheckInResource(personalVeh, None)
                  None
                } else {
                  currentTourPersonalVehicle
                }
              case None =>
                None
            },
            currentTourMode = if (activity.getType.equals("Home")) None else currentTourMode,
            hasDeparted = false
          )
        case Left(msg) =>
          logDebug(msg)
          val (_, triggerId) = releaseTickAndTriggerId()
          scheduler ! CompletionNotice(triggerId)
          stop
      }
  }

  val myUnhandled: StateFunction = {
    case Event(TriggerWithId(NotifyLegStartTrigger(_, _, _), _), _) =>
      stash()
      stay
    case Event(TriggerWithId(NotifyLegEndTrigger(_, _, _), _), _) =>
      stash()
      stay
    case Event(NotifyResourceInUse(_, _), _) =>
      stay()
    case Event(RegisterResource(_), _) =>
      stay()
    case Event(NotifyVehicleResourceIdle(_, _, _, _, _), _) =>
      stay()
    case Event(IllegalTriggerGoToError(reason), _) =>
      stop(Failure(reason))
    case Event(StateTimeout, _) =>
      log.error("Events leading up to this point:\n\t" + getLog.mkString("\n\t"))
      stop(
        Failure(
          "Timeout - this probably means this agent was not getting a reply it was expecting."
        )
      )
    case Event(Finish, _) =>
      if (stateName == Moving) {
        log.warning("Still travelling at end of simulation.")
        log.warning(s"Events leading up to this point:\n\t${getLog.mkString("\n\t")}")
      }
      stop
  }

  whenUnhandled(drivingBehavior.orElse(myUnhandled))

  override def logPrefix(): String = s"PersonAgent:$id "

}
