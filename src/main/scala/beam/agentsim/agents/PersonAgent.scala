package beam.agentsim.agents

import akka.actor.FSM.Failure
import akka.actor.{ActorRef, Props, Stash}
import beam.agentsim.Resource.{CheckInResource, NotifyResourceIdle, NotifyResourceInUse, RegisterResource}
import beam.agentsim.agents.BeamAgent._
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.agents.household.HouseholdActor.ReleaseVehicleReservation
import beam.agentsim.agents.modalBehaviors.ChoosesMode.ChoosesModeData
import beam.agentsim.agents.modalBehaviors.DrivesVehicle.{NotifyLegEndTrigger, NotifyLegStartTrigger, StartLegTrigger}
import beam.agentsim.agents.modalBehaviors.{ChoosesMode, DrivesVehicle, ModeChoiceCalculator}
import beam.agentsim.agents.planning.{BeamPlan, Tour}
import beam.agentsim.agents.vehicles._
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, IllegalTriggerGoToError, ScheduleTrigger}
import beam.agentsim.scheduler.{Trigger, TriggerWithId}
import beam.router.RoutingModel._
import beam.sim.{BeamServices, HasServices}
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

  def props(scheduler: ActorRef, services: BeamServices, modeChoiceCalculator: ModeChoiceCalculator, transportNetwork: TransportNetwork, router: ActorRef, rideHailingManager: ActorRef, eventsManager: EventsManager, personId: Id[PersonAgent], household: Household, plan: Plan,
            humanBodyVehicleId: Id[Vehicle]): Props = {
    Props(new PersonAgent(scheduler, services, modeChoiceCalculator, transportNetwork, router, rideHailingManager, eventsManager, personId, plan, humanBodyVehicleId))
  }

  trait PersonData

  case class BasePersonData(currentActivityIndex: Int = 0, currentTrip: Option[EmbodiedBeamTrip] = None, restOfCurrentTrip: Option[EmbodiedBeamTrip] = None, currentVehicle: VehicleStack = VehicleStack(), currentTourPersonalVehicle: Option[Id[Vehicle]] = None, hasDeparted: Boolean = false) extends PersonData {}

  case object PerformingActivity extends BeamAgentState

  sealed trait Traveling extends BeamAgentState

  case object ChoosingMode extends Traveling

  case object WaitingForReservationConfirmation extends Traveling

  case object Waiting extends Traveling

  case object ProcessingNextLegOrStartActivity extends Traveling

  case object WaitingToDrive extends Traveling

  case object Moving extends Traveling

  case object Driving extends Traveling

  case class ActivityStartTrigger(tick: Double) extends Trigger

  case class ActivityEndTrigger(tick: Double) extends Trigger

  case class PersonDepartureTrigger(tick: Double) extends Trigger

}

class PersonAgent(val scheduler: ActorRef, val beamServices: BeamServices, val modeChoiceCalculator: ModeChoiceCalculator, val transportNetwork: TransportNetwork, val router: ActorRef, val rideHailingManager: ActorRef, val eventsManager: EventsManager, override val id: Id[PersonAgent], val matsimPlan: Plan, val bodyId: Id[Vehicle]) extends BeamAgent[PersonData] with
  HasServices with ChoosesMode with DrivesVehicle[PersonData] with Stash {

  val _experiencedBeamPlan: BeamPlan = BeamPlan(matsimPlan)

  override def logDepth: Int = 100

  startWith(Uninitialized, BasePersonData())

  def activityOrMessage(ind: Int, msg: String): Either[String, Activity] = {
    if (ind < 0 || ind >= _experiencedBeamPlan.activities.length) Left(msg) else Right(_experiencedBeamPlan.activities(ind))
  }

  def currentActivity(data: BasePersonData): Activity = _experiencedBeamPlan.activities(data.currentActivityIndex)

  def nextActivity(data: BasePersonData): Either[String, Activity] = {
    activityOrMessage(data.currentActivityIndex + 1, "plan finished")
  }

  def prevActivity(data: BasePersonData): Either[String, Activity] = {
    activityOrMessage(data.currentActivityIndex - 1, "at start")
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
      goto(Initialized) replying CompletionNotice(triggerId, Vector(ScheduleTrigger(ActivityStartTrigger(0.0), self)))
  }

  when(Initialized) {
    case Event(TriggerWithId(ActivityStartTrigger(tick), triggerId), data: BasePersonData) =>
      logDebug(s"starting at ${currentActivity(data).getType} @ $tick")
      goto(PerformingActivity) replying CompletionNotice(triggerId, Vector(ScheduleTrigger(ActivityEndTrigger(currentActivity(data).getEndTime), self)))
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
          goto(ChoosingMode) using ChoosesModeData(data)
        }
      )
  }

  when(Waiting, stateTimeout = 1 second) {
    case Event(TriggerWithId(PersonDepartureTrigger(tick), triggerId), data@BasePersonData(_, Some(currentTrip),_,_,_,false)) =>
      // We end our activity when we actually leave, not when we decide to leave, i.e. when we look for a bus or
      // hail a ride. We stay at the party until our Uber is there.
      eventsManager.processEvent(new ActivityEndEvent(tick, id, currentActivity(data).getLinkId, currentActivity(data).getFacilityId, currentActivity(data).getType))
      assert(currentActivity(data).getLinkId != null)
      eventsManager.processEvent(new PersonDepartureEvent(tick, id, currentActivity(data).getLinkId, currentTrip.tripClassifier.value))
      holdTickAndTriggerId(tick, triggerId)
      goto(ProcessingNextLegOrStartActivity) using data.copy(hasDeparted = true)

    case Event(TriggerWithId(PersonDepartureTrigger(tick), triggerId), BasePersonData(_,_,_,_,_,true)) =>
      holdTickAndTriggerId(tick, triggerId)
      goto(ProcessingNextLegOrStartActivity)

    /*
     * Learn as passenger that leg is starting
     */
    case Event(TriggerWithId(NotifyLegStartTrigger(tick, beamLeg), triggerId), data@BasePersonData(_,_,Some(restOfCurrentTrip),currentVehicle,_, _)) if beamLeg == restOfCurrentTrip.legs.head.beamLeg =>
      logDebug(s"NotifyLegStartTrigger received: $beamLeg")
      if (restOfCurrentTrip.legs.head.beamVehicleId == currentVehicle.outermostVehicle()) {
        logDebug(s"Already on vehicle: ${currentVehicle.outermostVehicle()}")
        goto(Moving) replying CompletionNotice(triggerId)
      } else {
        eventsManager.processEvent(new PersonEntersVehicleEvent(tick, id, restOfCurrentTrip.legs.head.beamVehicleId))
        goto(Moving) replying CompletionNotice(triggerId) using data.copy(currentVehicle = currentVehicle.pushIfNew(restOfCurrentTrip.legs.head.beamVehicleId))
      }

    case Event(reservationResponse: ReservationResponse, data: BasePersonData) =>
      val (tick, triggerId) = releaseTickAndTriggerId()
      reservationResponse.response.fold(
          error => {
            logError("replanning")
            holdTickAndTriggerId(tick, triggerId)
            goto(ChoosingMode) using ChoosesModeData(data)
          },
          confirmation => {
            scheduler ! CompletionNotice(triggerId)
            stay()
          }
        )
  }

  when(Moving) {
    /*
     * Learn as passenger that leg is ending
     */
    case Event(TriggerWithId(NotifyLegEndTrigger(tick, beamLeg), triggerId), data@BasePersonData(_,_,Some(restOfCurrentTrip),currentVehicle,_,_)) if beamLeg == restOfCurrentTrip.legs.head.beamLeg =>
      if (restOfCurrentTrip.legs.tail.head.beamVehicleId == currentVehicle.outermostVehicle()) {
        // The next vehicle is the same as current so just update state and go to Waiting
        goto(Waiting) replying CompletionNotice(triggerId) using data.copy(restOfCurrentTrip = Some(restOfCurrentTrip.copy(legs = restOfCurrentTrip.legs.tail)))
      } else {
        // The next vehicle is different from current so we exit the current vehicle
        eventsManager.processEvent(new PersonLeavesVehicleEvent(tick, id, currentVehicle.outermostVehicle()))
        holdTickAndTriggerId(tick, triggerId)
        goto(ProcessingNextLegOrStartActivity) using data.copy(
          restOfCurrentTrip = Some(restOfCurrentTrip.copy(legs = restOfCurrentTrip.legs.tail)),
          currentVehicle = currentVehicle.pop()
        )
      }
  }

  // Callback from DrivesVehicle. Analogous to NotifyLegEndTrigger, but when driving ourselves.
  override def passengerScheduleEmpty(tick: Double, triggerId: Long): State = {
    val data = stateData.asInstanceOf[BasePersonData]
    if (data.restOfCurrentTrip.get.legs.head.unbecomeDriverOnCompletion) {
      beamServices.vehicles(data.currentVehicle.outermostVehicle()).unsetDriver()
      eventsManager.processEvent(new PersonLeavesVehicleEvent(tick, Id.createPersonId(id), data.currentVehicle.outermostVehicle()))
      if (!data.currentVehicle.pop().isEmpty) {
        _currentVehicleUnderControl = Some(beamServices.vehicles(data.currentVehicle.pop().outermostVehicle()))
      }
    }
    holdTickAndTriggerId(tick, triggerId)
    goto(ProcessingNextLegOrStartActivity) using data.copy(
      restOfCurrentTrip = Some(data.restOfCurrentTrip.get.copy(legs = data.restOfCurrentTrip.get.legs.tail)),
      currentVehicle = if (data.restOfCurrentTrip.get.legs.head.unbecomeDriverOnCompletion) {
        data.currentVehicle.pop()
      } else {
        data.currentVehicle
      }
    )
  }

  onTransition {
    case _ -> _ =>
      unstashAll()
  }

  /*
   * processNextLegOrStartActivity
   *
   * This should be called when it's time to either embark on another leg in a trip or to wrap up a trip that is
   * now complete. There are four outcomes possible:
   *
   * 1 There are more legs in the trip and the PersonAgent is the driver => stay in current state but schedule
   * StartLegTrigger
   * 2 There are more legs in the trip but the PersonAGent is a passenger => goto Waiting and schedule nothing
   * further (the driver will initiate the start of the leg)
   * 3 The trip is over and there are more activities in the agent plan => goto PerformingActivity and schedule end
   * of activity
   * 4 The trip is over and there are no more activities in the agent plan => goto Finished
   */
  when(ProcessingNextLegOrStartActivity, stateTimeout = Duration.Zero) {
    case Event(StateTimeout, data@BasePersonData(currentActivityIndex, Some(currentTrip),Some(restOfCurrentTrip),currentVehicle,currentTourPersonalVehicle, _)) =>
      (restOfCurrentTrip.legs.headOption, nextActivity(data)) match {
        case (Some(nextLeg), _) if nextLeg.asDriver =>
          passengerSchedule = PassengerSchedule()
          passengerSchedule.addLegs(Vector(nextLeg.beamLeg))
          val (tick, triggerId) = releaseTickAndTriggerId()
          if (currentVehicle.isEmpty || currentVehicle.outermostVehicle() != nextLeg.beamVehicleId) {
            val vehicle = beamServices.vehicles(nextLeg.beamVehicleId)
            vehicle.becomeDriver(self).fold(fa =>
              stop(Failure(s"I attempted to become driver of vehicle $id but driver ${vehicle.driver.get} already assigned.")),
              fb => {
                _currentVehicleUnderControl = Some(vehicle)
                eventsManager.processEvent(new PersonEntersVehicleEvent(tick, Id.createPersonId(id), nextLeg.beamVehicleId))
              })
          }

          // Can't depart earlier than it is now
          val newTriggerTime = math.max(nextLeg.beamLeg.startTime, tick)
          scheduler ! CompletionNotice(triggerId, Vector(ScheduleTrigger(StartLegTrigger(newTriggerTime, nextLeg.beamLeg), self)))
          goto(WaitingToDrive) using data.copy(currentVehicle = currentVehicle.pushIfNew(nextLeg.beamVehicleId))
        case (Some(nextLeg), _) if nextLeg.beamLeg.mode.isTransit() =>
          val legSegment = restOfCurrentTrip.legs.takeWhile(leg => leg.beamVehicleId == nextLeg.beamVehicleId)
          val resRequest = new ReservationRequest(legSegment.head.beamLeg, legSegment.last.beamLeg, VehiclePersonId(legSegment.head.beamVehicleId, id))
          TransitDriverAgent.selectByVehicleId(legSegment.head.beamVehicleId) ! resRequest
          goto(Waiting)
        case (Some(_), _) =>
          val (_, triggerId) = releaseTickAndTriggerId()
          scheduler ! CompletionNotice(triggerId)
          goto(Waiting)
        case (None, Right(activity)) =>
          val (tick, triggerId) = releaseTickAndTriggerId()
          val endTime = if (activity.getEndTime >= tick && Math.abs(activity.getEndTime) < Double.PositiveInfinity) {
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
          eventsManager.processEvent(new TeleportationArrivalEvent(tick, id, currentTrip.legs.map(l => l.beamLeg.travelPath.distanceInM).sum))
          assert(activity.getLinkId != null)
          eventsManager.processEvent(new PersonArrivalEvent(tick, id, activity.getLinkId, currentTrip.tripClassifier.value))
          eventsManager.processEvent(new ActivityStartEvent(tick, id, activity.getLinkId, activity.getFacilityId, activity.getType))
          scheduler ! CompletionNotice(triggerId, Vector(ScheduleTrigger(ActivityEndTrigger(endTime), self)))
          goto(PerformingActivity) using data.copy(
            currentActivityIndex = currentActivityIndex + 1,
            currentTrip = None,
            restOfCurrentTrip = None,
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
            hasDeparted = false
          )
        case (None, Left(msg)) =>
          logDebug(msg)
          val (_, triggerId) = releaseTickAndTriggerId()
          scheduler ! CompletionNotice(triggerId)
          stop
      }

  }

  val myUnhandled: StateFunction = {
    case Event(TriggerWithId(NotifyLegStartTrigger(_, _), _), _) =>
      stash()
      stay
    case Event(TriggerWithId(NotifyLegEndTrigger(_, _), _), _) =>
      stash()
      stay
    case Event(NotifyResourceInUse(_, _), _) =>
      stay()
    case Event(RegisterResource(_), _) =>
      stay()
    case Event(NotifyResourceIdle(_, _), _) =>
      stay()
    case Event(IllegalTriggerGoToError(reason), _) =>
      stop(Failure(reason))
    case Event(StateTimeout, _) =>
      log.error("Events leading up to this point:\n\t" + getLog.mkString("\n\t"))
      stop(Failure("Timeout - this probably means this agent was not getting a reply it was expecting."))
    case Event(Finish, _) =>
      log.warning("Still travelling at end of simulation.")
      log.warning("Events leading up to this point:\n\t" + getLog.mkString("\n\t"))
      stop
  }

  whenUnhandled(drivingBehavior.orElse(myUnhandled))

  override def logPrefix(): String = s"PersonAgent:$id "

}






