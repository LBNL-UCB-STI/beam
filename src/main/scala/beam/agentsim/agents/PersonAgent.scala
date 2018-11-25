package beam.agentsim.agents

import akka.actor.FSM.Failure
import akka.actor.{ActorRef, FSM, Props, Stash}
import beam.agentsim.Resource.{CheckInResource, NotifyResourceInUse, RegisterResource}
import beam.agentsim.ResourceManager.NotifyVehicleResourceIdle
import beam.agentsim.agents.BeamAgent._
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.agents.household.HouseholdActor.ReleaseVehicleReservation
import beam.agentsim.agents.modalbehaviors.ChoosesMode.ChoosesModeData
import beam.agentsim.agents.modalbehaviors.DrivesVehicle.{AlightVehicleTrigger, BoardVehicleTrigger, StartLegTrigger}
import beam.agentsim.agents.modalbehaviors.{ChoosesMode, DrivesVehicle, ModeChoiceCalculator}
import beam.agentsim.agents.parking.ChoosesParking
import beam.agentsim.agents.parking.ChoosesParking.{ChoosingParkingSpot, ReleasingParkingSpot}
import beam.agentsim.agents.planning.{BeamPlan, Tour}
import beam.agentsim.agents.ridehail._
import beam.agentsim.agents.vehicles.VehicleProtocol.{
  BecomeDriverOfVehicleSuccess,
  DriverAlreadyAssigned,
  NewDriverAlreadyControllingVehicle
}
import beam.agentsim.agents.vehicles._
import beam.agentsim.events.resources.ReservationError
import beam.agentsim.events.{PersonCostEvent, ReplanningEvent, ReserveRideHailEvent}
import beam.agentsim.infrastructure.ParkingManager.ParkingInquiryResponse
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, IllegalTriggerGoToError, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{CAR, NONE, WALK_TRANSIT}
import beam.router.model.{EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.router.osm.TollCalculator
import beam.sim.BeamServices
import beam.sim.population.AttributesOfIndividual
import beam.utils.logging.ExponentialLazyLogging
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

  type VehicleStack = Vector[Id[Vehicle]]
  val timeToChooseMode: Double = 0.0
  val minActDuration: Double = 0.0
  val teleportWalkDuration = 0.0

  def props(
    scheduler: ActorRef,
    services: BeamServices,
    modeChoiceCalculator: ModeChoiceCalculator,
    transportNetwork: TransportNetwork,
    tollCalculator: TollCalculator,
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
        parkingManager,
        tollCalculator
      )
    )
  }

  sealed trait Traveling extends BeamAgentState

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

  case class ActivityStartTrigger(tick: Int) extends Trigger

  case class ActivityEndTrigger(tick: Int) extends Trigger

  case class PersonDepartureTrigger(tick: Int) extends Trigger

  case object PerformingActivity extends BeamAgentState

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
  val parkingManager: ActorRef,
  val tollCalculator: TollCalculator
) extends DrivesVehicle[PersonData]
    with ChoosesMode
    with ChoosesParking
    with Stash {

  val attributes: AttributesOfIndividual =
    matsimPlan.getPerson.getCustomAttributes
      .get("beam-attributes")
      .asInstanceOf[AttributesOfIndividual]

  val _experiencedBeamPlan: BeamPlan = BeamPlan(matsimPlan)

  val myUnhandled: StateFunction = {
    case Event(TriggerWithId(BoardVehicleTrigger(_, _), _), _) =>
      stash()
      stay
    case Event(TriggerWithId(AlightVehicleTrigger(_, _), _), _) =>
      stash()
      stay
    case Event(NotifyResourceInUse(_, _), _) =>
      stay()
    case Event(RegisterResource(_), _) =>
      stay()
    case Event(NotifyVehicleResourceIdle(_, _, _, _, _), _) =>
      stay()
    case Event(ParkingInquiryResponse(_, _), _) =>
      stop(Failure("Unexpected ParkingInquiryResponse"))
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

  override def logDepth: Int = beamServices.beamConfig.beam.debug.actor.logDepth

  startWith(Uninitialized, BasePersonData())

  def scaleTimeByValueOfTime(time: Double, beamMode: Option[BeamMode] = None): Double =
    modeChoiceCalculator.scaleTimeByVot(time, beamMode)

  def currentTour(data: BasePersonData): Tour = {
    stateName match {
      case PerformingActivity =>
        _experiencedBeamPlan.getTourContaining(currentActivity(data))
      case _ =>
        _experiencedBeamPlan.getTourContaining(nextActivity(data).get)
    }
  }

  def currentActivity(data: BasePersonData): Activity =
    _experiencedBeamPlan.activities(data.currentActivityIndex)

  def nextActivity(data: BasePersonData): Option[Activity] = {
    val ind = data.currentActivityIndex + 1
    if (ind < 0 || ind >= _experiencedBeamPlan.activities.length) {
      None
    } else {
      Some(_experiencedBeamPlan.activities(ind))
    }
  }

  when(Uninitialized) {
    case Event(TriggerWithId(InitializeTrigger(_), triggerId), _) =>
      goto(Initialized) replying CompletionNotice(
        triggerId,
        Vector(ScheduleTrigger(ActivityStartTrigger(0), self))
      )
  }

  when(Initialized) {
    case Event(TriggerWithId(ActivityStartTrigger(tick), triggerId), data: BasePersonData) =>
      logDebug(s"starting at ${currentActivity(data).getType} @ $tick")
      goto(PerformingActivity) replying CompletionNotice(
        triggerId,
        Vector(ScheduleTrigger(ActivityEndTrigger(currentActivity(data).getEndTime.toInt), self))
      )
  }

  when(PerformingActivity) {
    case Event(TriggerWithId(ActivityEndTrigger(tick), triggerId), data: BasePersonData) =>
      nextActivity(data) match {
        case None =>
          logDebug(s"didn't get nextActivity")
          stop replying CompletionNotice(triggerId)
        case Some(nextAct) =>
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

  def activityOrMessage(ind: Int, msg: String): Either[String, Activity] = {
    if (ind < 0 || ind >= _experiencedBeamPlan.activities.length) Left(msg)
    else Right(_experiencedBeamPlan.activities(ind))
  }

  def handleFailedRideHailReservation(
    error: ReservationError,
    response: RideHailResponse,
    data: BasePersonData
  ): State = {
    logDebug(s"replanning because ${error.errorCode}")
    eventsManager.processEvent(new ReplanningEvent(_currentTick.get, Id.createPersonId(id)))
    goto(ChoosingMode) using ChoosesModeData(
      data.copy(currentTourMode = None),
      currentLocation = Some(beamServices.geo.wgs2Utm(data.restOfCurrentTrip.head.beamLeg.travelPath.startPoint)),
      isWithinTripReplanning = true
    )
  }

  when(WaitingForReservationConfirmation) {
    // TRANSIT SUCCESS
    case Event(ReservationResponse(_, Right(response), _), data: BasePersonData) =>
      handleSuccessfulReservation(response.triggersToSchedule, data)
    // TRANSIT FAILURE
    case Event(
        ReservationResponse(_, Left(firstErrorResponse), _),
        data @ BasePersonData(_, _, nextLeg :: _, _, _, _, _, _, _)
        ) =>
      logDebug(s"replanning because ${firstErrorResponse.errorCode}")
      eventsManager.processEvent(new ReplanningEvent(_currentTick.get, Id.createPersonId(id)))
      goto(ChoosingMode) using ChoosesModeData(
        data,
        currentLocation = Some(beamServices.geo.wgs2Utm(nextLeg.beamLeg.travelPath.startPoint)),
        isWithinTripReplanning = true
      )
    // RIDE HAIL DELAY
    case Event(DelayedRideHailResponse, data: BasePersonData) =>
      // this means ride hail manager is taking time to assign and we should complete our
      // current trigger and wait to be re-triggered by the manager
      val (_, triggerId) = releaseTickAndTriggerId()
      scheduler ! CompletionNotice(triggerId, Vector())
      stay() using data
    // RIDE HAIL DELAY FAILURE
    // we use trigger for this to get triggerId back into hands of the person
    case Event(
        TriggerWithId(RideHailResponseTrigger(tick, response @ RideHailResponse(_, _, Some(error), _)), triggerId),
        data: BasePersonData
        ) =>
      holdTickAndTriggerId(tick, triggerId)
      handleFailedRideHailReservation(error, response, data)
    // RIDE HAIL SUCCESS
    // no trigger needed here since we're going to Waiting anyway without any other actions needed
    case Event(RideHailResponse(_, _, None, triggersToSchedule), data: BasePersonData) =>
      handleSuccessfulReservation(triggersToSchedule, data)
    // RIDE HAIL FAILURE
    case Event(
        response @ RideHailResponse(_, _, Some(error), _),
        data @ BasePersonData(_, _, _, _, _, _, _, _, _)
        ) =>
      handleFailedRideHailReservation(error, response, data)
  }

  when(Waiting) {
    /*
     * Learn as passenger that it is time to board the vehicle
     */
    case Event(
        TriggerWithId(BoardVehicleTrigger(tick, vehicleToEnter), triggerId),
        data @ BasePersonData(_, _, _ :: _, currentVehicle, _, _, _, _, _)
        ) =>
      logDebug(s"PersonEntersVehicle: $vehicleToEnter")
      eventsManager.processEvent(new PersonEntersVehicleEvent(tick, id, vehicleToEnter))

      val mode = data.currentTrip.get.tripClassifier
      val estimateCost = data.currentTrip.get.costEstimate
      val subsidy = beamServices.modeSubsidies.computeSubsidy(attributes, data.currentTrip.get.vehiclesInTrip, mode)

      if ((estimateCost + subsidy) != 0.0)
        eventsManager.processEvent(
          new PersonCostEvent(
            tick,
            id,
            mode.value,
            PersonCostEvent.COST_TYPE_COST,
            estimateCost + subsidy
          )
        )

      if (subsidy != 0.0)
        eventsManager.processEvent(
          new PersonCostEvent(tick, id, mode.value, PersonCostEvent.COST_TYPE_SUBSIDY, subsidy)
        )

      goto(Moving) replying CompletionNotice(triggerId) using data.copy(
        currentVehicle = vehicleToEnter +: currentVehicle
      )
  }

  when(Moving) {
    /*
     * Learn as passenger that it is time to alight the vehicle
     */
    case Event(
        TriggerWithId(AlightVehicleTrigger(tick, vehicleToExit), triggerId),
        data @ BasePersonData(_, _, _ :: restOfCurrentTrip, currentVehicle, _, _, _, _, _)
        ) =>
      logDebug(s"PersonLeavesVehicle: $vehicleToExit")
      eventsManager.processEvent(new PersonLeavesVehicleEvent(tick, id, vehicleToExit))
      holdTickAndTriggerId(tick, triggerId)
      goto(ProcessingNextLegOrStartActivity) using data.copy(
        restOfCurrentTrip = restOfCurrentTrip.dropWhile(leg => leg.beamVehicleId == vehicleToExit),
        currentVehicle = currentVehicle.tail
      )
  }

  // Callback from DrivesVehicle. Analogous to AlightVehicleTrigger, but when driving ourselves.
  when(PassengerScheduleEmpty) {
    case Event(PassengerScheduleEmptyMessage(_, toll), data: BasePersonData) =>
      val (tick, triggerId) = releaseTickAndTriggerId()
      if (toll != 0.0)
        eventsManager.processEvent(
          new PersonCostEvent(tick, matsimPlan.getPerson.getId, "car", "toll", toll)
        )
      if (data.restOfCurrentTrip.head.unbecomeDriverOnCompletion) {
        val theVehicle = beamServices.vehicles(data.currentVehicle.head)
        theVehicle.unsetDriver()
        nextNotifyVehicleResourceIdle match {
          case Some(nextNotify) =>
            //            context.parent ! nextNotify
            theVehicle.manager.foreach(
              _ ! nextNotify
            )
          case None =>
        }
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
        data @ BasePersonData(_, _, _ :: theRestOfCurrentTrip, _, _, _, _, _, _)
        ) =>
      log.debug("ReadyToChooseParking, restoftrip: {}", theRestOfCurrentTrip.toString())
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
          log.debug(
            "ProcessingNextLegOrStartActivity, going to ReleasingParkingSpot with legsToInclude: {}",
            legsToInclude
          )
          (ReleasingParkingSpot, _currentTick.get)
        } else {
          val (currentTick, _) = releaseTickAndTriggerId()
          (WaitingToDrive, currentTick)
        }

      val currentVehicleForNextState =
        if (currentVehicle.isEmpty || currentVehicle.head != nextLeg.beamVehicleId) {
          val vehicle = beamServices.vehicles(nextLeg.beamVehicleId)
          vehicle.becomeDriver(self) match {
            case DriverAlreadyAssigned(currentDriver) =>
              log.error(
                "I attempted to become driver of vehicle {} but driver {} already assigned.",
                vehicle.id,
                currentDriver
              )
              None
            case NewDriverAlreadyControllingVehicle =>
              log.debug(
                "I attempted to become driver of vehicle {} but I already am driving this vehicle (person {})",
                vehicle.id,
                id
              )
              Some(currentVehicle)
            case BecomeDriverOfVehicleSuccess =>
              eventsManager.processEvent(
                new PersonEntersVehicleEvent(
                  currentTick,
                  Id.createPersonId(id),
                  nextLeg.beamVehicleId
                )
              )
              Some(nextLeg.beamVehicleId +: currentVehicle)
          }
        } else {
          Some(currentVehicle)
        }
      if (currentVehicleForNextState.isDefined) {
        val newPassengerSchedule = PassengerSchedule().addLegs(legsToInclude.map(_.beamLeg))
        goto(stateToGo) using data.copy(
          passengerSchedule = newPassengerSchedule,
          currentLegPassengerScheduleIndex = 0,
          currentVehicle = currentVehicleForNextState.get
        )
      } else {
        stop(
          Failure(
            s"Person $id attempted to become driver of vehicle ${nextLeg.beamVehicleId} but driver already assigned."
          )
        )
      }
    // TRANSIT but too late
    case Event(StateTimeout, data @ BasePersonData(_, _, nextLeg :: _, _, _, _, _, _, _))
        if nextLeg.beamLeg.startTime < _currentTick.get =>
      // We've missed the bus. This occurs when the actual ride hail trip takes much longer than planned (based on the
      // initial inquiry). So we replan but change tour mode to WALK_TRANSIT since we've already done our ride hail portion.
      ExponentialLazyLogging.logger.warn(
        "Missed transit pickup during a ride_hail_transit trip, late by {} sec",
        _currentTick.get - nextLeg.beamLeg.startTime
      )

      goto(ChoosingMode) using ChoosesModeData(
        personData = data.copy(currentTourMode = Some(WALK_TRANSIT)),
        currentLocation = Some(beamServices.geo.wgs2Utm(nextLeg.beamLeg.travelPath.startPoint)),
        isWithinTripReplanning = true
      )
    // TRANSIT
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
    // RIDE_HAIL
    case Event(StateTimeout, BasePersonData(_, _, nextLeg :: tailOfCurrentTrip, _, _, _, _, _, _))
        if nextLeg.isRideHail =>
      val legSegment = nextLeg :: tailOfCurrentTrip.takeWhile(
        leg => leg.beamVehicleId == nextLeg.beamVehicleId
      )
      val departAt = legSegment.head.beamLeg.startTime

      rideHailManager ! RideHailRequest(
        ReserveRide,
        VehiclePersonId(bodyId, id, Some(self)),
        beamServices.geo.wgs2Utm(nextLeg.beamLeg.travelPath.startPoint.loc),
        departAt,
        beamServices.geo.wgs2Utm(legSegment.last.beamLeg.travelPath.endPoint.loc),
        nextLeg.isPooledTrip
      )

      eventsManager.processEvent(
        new ReserveRideHailEvent(
          _currentTick.getOrElse(departAt).toDouble,
          id,
          departAt,
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
        case Some(activity) =>
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
            Vector(ScheduleTrigger(ActivityEndTrigger(endTime.toInt), self))
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
        case None =>
          logDebug("PersonAgent nextActivity returned None")
          val (_, triggerId) = releaseTickAndTriggerId()
          scheduler ! CompletionNotice(triggerId)
          stop
      }
  }

  def handleSuccessfulReservation(
    triggersToSchedule: Vector[ScheduleTrigger],
    data: BasePersonData
  ): FSM.State[BeamAgentState, PersonData] = {
    if (_currentTriggerId.isDefined) {
      val (_, triggerId) = releaseTickAndTriggerId()
      log.debug("scheduling triggers from reservation responses: {}", triggersToSchedule)
      scheduler ! CompletionNotice(triggerId, triggersToSchedule)
    } else {
      // if _currentTriggerId is empty, this means we have received the reservation response from a batch
      // vehicle allocation process. It's ok, the trigger is with the ride hail manager.
    }
    goto(Waiting) using data

  }

  whenUnhandled(drivingBehavior.orElse(myUnhandled))

  override def logPrefix(): String = s"PersonAgent:$id "

}
