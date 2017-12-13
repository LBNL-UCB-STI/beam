package beam.agentsim.agents

import akka.actor.FSM.Failure
import akka.actor.{ActorRef, Props}
import beam.agentsim.Resource.TellManagerResourceIsAvailable
import beam.agentsim.agents.BeamAgent._
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.agents.TriggerUtils._
import beam.agentsim.agents.household.HouseholdActor.{NotifyNewVehicleLocation, ReleaseVehicleReservation}
import beam.agentsim.agents.modalBehaviors.ChoosesMode.BeginModeChoiceTrigger
import beam.agentsim.agents.modalBehaviors.DrivesVehicle.{NotifyLegEndTrigger, NotifyLegStartTrigger, StartLegTrigger}
import beam.agentsim.agents.modalBehaviors.{ChoosesMode, DrivesVehicle}
import beam.agentsim.agents.vehicles.BeamVehicleType._
import beam.agentsim.agents.vehicles.VehicleProtocol._
import beam.agentsim.agents.vehicles._
import beam.agentsim.events.SpaceTime
import beam.agentsim.scheduler.BeamAgentScheduler.IllegalTriggerGoToError
import beam.agentsim.scheduler.{Trigger, TriggerWithId}
import beam.router.Modes
import beam.router.RoutingModel._
import beam.sim.{BeamServices, HasServices}
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events._
import org.matsim.api.core.v01.population._
import org.matsim.households.Household
import org.matsim.vehicles.Vehicle
import org.slf4j.LoggerFactory

/**
  */
object PersonAgent {

  private val ActorPrefixName = "person-"
  val timeToChooseMode: Double = 0.0
  val minActDuration: Double = 0.0
  val teleportWalkDuration = 0.0

  private val logger = LoggerFactory.getLogger(classOf[PersonAgent])

  def props(services: BeamServices, personId: Id[PersonAgent], householdId: Id[Household], plan: Plan,
            humanBodyVehicleId: Id[Vehicle]): Props = {
    Props(new PersonAgent(services, personId, householdId, plan, humanBodyVehicleId))
  }

  def buildActorName(personId: Id[Person]): String = {
    s"$ActorPrefixName${personId.toString}"
  }

  case class PersonData() extends BeamAgentData {}

  object PersonData {

    import scala.collection.JavaConverters._

    def planToVec(plan: Plan): Vector[Activity] = {
      scala.collection.immutable.Vector.empty[Activity] ++ plan.getPlanElements.asScala.filter(p => p
        .isInstanceOf[Activity]).map(p => p.asInstanceOf[Activity])
    }
  }

  sealed trait InActivity extends BeamAgentState

  case object PerformingActivity extends InActivity

  sealed trait Traveling extends BeamAgentState

  case object ChoosingMode extends Traveling

  case object Waiting extends Traveling

  case object Moving extends Traveling

  case class ResetPersonAgent(tick: Double) extends Trigger

  case class ActivityStartTrigger(tick: Double) extends Trigger

  case class ActivityEndTrigger(tick: Double) extends Trigger

  case class RouteResponseWrapper(tick: Double, triggerId: Long, alternatives: Vector[BeamTrip]) extends Trigger

  case class RideHailingInquiryTrigger(tick: Double, triggerId: Long, alternatives: Vector[BeamTrip],
                                       timesToCustomer: Vector[Double]) extends Trigger

  case class MakeRideHailingReservationResponseWrapper(tick: Double, triggerId: Long,
                                                       rideHailingAgentOpt: Option[ActorRef], timeToCustomer: Double,
                                                       tripChoice: BeamTrip) extends Trigger

  case class FinishWrapper(tick: Double, triggerId: Long) extends Trigger

  case class NextActivityWrapper(tick: Double, triggerId: Long) extends Trigger

  case class PersonDepartureTrigger(tick: Double) extends Trigger

  case class PersonEntersRideHailingVehicleTrigger(tick: Double) extends Trigger

  case class PersonLeavesRideHailingVehicleTrigger(tick: Double) extends Trigger

  case class PersonEntersBoardingQueueTrigger(tick: Double) extends Trigger

  case class PersonEntersAlightingQueueTrigger(tick: Double) extends Trigger

  case class PersonArrivesTransitStopTrigger(tick: Double) extends Trigger

  case class PersonArrivalTrigger(tick: Double) extends Trigger

  case class TeleportationArrivalTrigger(tick: Double) extends Trigger

  case class CompleteDrivingMissionTrigger(tick: Double) extends Trigger

  case class PassengerScheduleEmptyTrigger(tick: Double) extends Trigger

}

class PersonAgent(val beamServices: BeamServices,
                  override val id: Id[PersonAgent],
                  val householdId: Id[Household],
                  val matsimPlan: Plan,
                  humanBodyVehicleId: Id[Vehicle],
                  override val data: PersonData = PersonData()) extends BeamAgent[PersonData] with
  HasServices with ChoosesMode with DrivesVehicle[PersonData] {

  var _activityChain: Vector[Activity] = PersonData.planToVec(matsimPlan)
  var _currentActivityIndex: Int = 0
  var _currentVehicle: VehicleStack = VehicleStack()
  var _humanBodyVehicle: Id[Vehicle] = humanBodyVehicleId
  var _currentRoute: EmbodiedBeamTrip = EmbodiedBeamTrip.empty
  var _currentTripMode: Option[Modes.BeamMode] = None
  var _currentEmbodiedLeg: Option[EmbodiedBeamLeg] = None
  var _household: Id[Household] = householdId
  var _numReschedules: Int = 0

  def activityOrMessage(ind: Int, msg: String): Either[String, Activity] = {
    if (ind < 0 || ind >= _activityChain.length) Left(msg) else Right(_activityChain(ind))
  }

  def currentActivity: Activity = _activityChain(_currentActivityIndex)

  def nextActivity: Either[String, Activity] = {
    activityOrMessage(_currentActivityIndex + 1, "plan finished")
  }

  def prevActivity: Either[String, Activity] = {
    activityOrMessage(_currentActivityIndex - 1, "at start")
  }

  when(PerformingActivity) {
    case ev@Event(_, _) =>
      handleEvent(stateName, ev)
  }
  when(ChoosingMode) {
    case ev@Event(_, _) =>
      handleEvent(stateName, ev)
    case msg@_ =>
      stop(Failure(s"Unrecognized message $msg from state ChoosingMode"))
  }
  when(Waiting) {
    case ev@Event(_, _) =>
      handleEvent(stateName, ev)
    case msg@_ =>
      stop(Failure(s"Unrecognized message $msg from state Waiting"))
  }
  when(Moving) {
    case ev@Event(_, _) =>
      handleEvent(stateName, ev)
    case msg@_ =>
      stop(Failure(s"Unrecognized message $msg from state Moving"))
  }

  chainedWhen(Uninitialized) {
    case Event(TriggerWithId(InitializeTrigger(_), triggerId), _) =>
      goto(Initialized) replying completed(triggerId, schedule[ActivityStartTrigger](0.0, self))


  }

  chainedWhen(Initialized) {


    case Event(TriggerWithId(ActivityStartTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      val currentAct = currentActivity
      logInfo(s"starting at ${currentAct.getType} @ $tick")
      goto(PerformingActivity) using info replying completed(triggerId, schedule[ActivityEndTrigger](currentAct
        .getEndTime, self))
  }

  chainedWhen(PerformingActivity) {
    case Event(TriggerWithId(ActivityEndTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      val currentAct = currentActivity
      nextActivity.fold(
        msg => {
          logInfo(s"didn't get nextActivity because $msg")
          stop replying completed(triggerId)
        },
        nextAct => {
          logInfo(s"going to ${nextAct.getType} @ $tick")
          context.system.eventStream.publish(new ActivityEndEvent(tick, id, currentAct.getLinkId,
            currentAct.getFacilityId, currentAct.getType))
          goto(ChoosingMode) replying completed(triggerId, schedule[BeginModeChoiceTrigger](tick, self))
        }
      )
  }

  private def warnAndRescheduleNotifyLeg(tick: Double, triggerId: Long, beamLeg: BeamLeg, isStart: Boolean = true) = {

    _numReschedules = _numReschedules + 1
    if (_numReschedules > 500) {
      cancelTrip(_currentRoute.legs, _currentVehicle)
      stop(Failure(s"Too many reschedule attempts."))
    } else {
      val toSchedule = if (isStart) {
        schedule[NotifyLegStartTrigger](tick, self, beamLeg)
      } else {
        schedule[NotifyLegEndTrigger](tick, self, beamLeg)
      }
      logWarn(s"Rescheduling: $toSchedule")
      stay() replying completed(triggerId, toSchedule)
    }
  }

  chainedWhen(Waiting) {


    /*
     * Starting Trip
     */
    case Event(TriggerWithId(PersonDepartureTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      _currentTripMode = Some(_currentRoute.tripClassifier)
      context.system.eventStream.publish(new PersonDepartureEvent(tick, id, currentActivity.getLinkId,
        _currentTripMode.get.matsimMode))
      processNextLegOrStartActivity(triggerId, tick)
    /*
     * Complete leg(s) as driver
     */
    case Event(TriggerWithId(PassengerScheduleEmptyTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      processNextLegOrStartActivity(triggerId, tick)
    /*
     * Learn as passenger that leg is starting
     */
    case Event(TriggerWithId(NotifyLegStartTrigger(tick, beamLeg), triggerId), _) =>
      logDebug(s"NotifyLegStartTrigger received: ${beamLeg}")
      _currentEmbodiedLeg match {
        /*
         * If we already have a leg then we're not ready to start a new one,
         * this occurs when a transit driver is ready to roll but an agent hasn't
         * finished previous leg.
         * Solution for now is to re-send this to self, but this could get expensive...
         */
        case Some(_) =>
          warnAndRescheduleNotifyLeg(tick, triggerId, beamLeg)
        case None =>
          val processedDataOpt = breakTripIntoNextLegAndRestOfTrip(_currentRoute, tick)
          processedDataOpt match {
            case Some(processedData) =>
              if (processedData.nextLeg.beamLeg != beamLeg || processedData.nextLeg.asDriver) {
                // We've recevied this leg out of order from 2 different drivers or we haven't our
                // personDepartureTrigger
                warnAndRescheduleNotifyLeg(tick, triggerId, beamLeg)
              } else if (processedData.nextLeg.beamVehicleId == _currentVehicle.outermostVehicle()) {
                logDebug(s"Already on vehicle: ${_currentVehicle.outermostVehicle()}")
                _currentRoute = processedData.restTrip
                _currentEmbodiedLeg = Some(processedData.nextLeg)
                goto(Moving) replying completed(triggerId)
              } else {
                val previousVehicleId = _currentVehicle.outermostVehicle()
                val nextBeamVehicleId = processedData.nextLeg.beamVehicleId

                // Send message to driver we're entering vehicle
                // Note that here we enter vehicle regardless of its capacity (!)

                // TODO: Instead of maintaining references to the vehicle, we should maintain the ref
                //       to the driver (associated with vehicleID)
                beamServices.vehicles(nextBeamVehicleId).driver.foreach(
                  driver =>
                    driver ! BoardVehicle(tick, VehiclePersonId(previousVehicleId, id))
                )

                _currentRoute = processedData.restTrip
                _currentEmbodiedLeg = Some(processedData.nextLeg)
                _currentVehicle = _currentVehicle.pushIfNew(nextBeamVehicleId)
                goto(Moving) replying completed(triggerId)
              }
            case None =>
              stop(Failure(s"Expected a non-empty BeamTrip but found ${_currentRoute}"))
          }
      }


    case Event(TriggerWithId(NotifyLegEndTrigger(tick, beamLeg), triggerId), _) =>
      warnAndRescheduleNotifyLeg(tick, triggerId, beamLeg, false)
  }

  chainedWhen(Moving) {
    /*
     * Learn as passenger that leg is ending
     */
    case Event(TriggerWithId(NotifyLegEndTrigger(tick, beamLeg), triggerId), _) =>
      _currentEmbodiedLeg match {
        case Some(currentLeg) if currentLeg.beamLeg == beamLeg =>
          val processedDataOpt = breakTripIntoNextLegAndRestOfTrip(_currentRoute, tick)
          processedDataOpt match {
            case Some(processedData) => // There are more legs in the trip...
              if (processedData.nextLeg.beamVehicleId == _currentVehicle.outermostVehicle()) {
                // The next vehicle is the same as current so just update state and go to Waiting
                _currentEmbodiedLeg = None
                goto(Waiting) replying completed(triggerId)
              } else {
                // The next vehicle is different from current so we exit the current vehicle
                val passengerVehicleId = _currentVehicle.penultimateVehicle()
                beamServices.vehicles(_currentVehicle.outermostVehicle()).driver.get ! AlightVehicle(tick,
                  VehiclePersonId(passengerVehicleId, id))
                _currentVehicle = _currentVehicle.pop()
                // Note that this will send a scheduling reply to a driver, not the scheduler, the driver must pass
                // on the new trigger
                processNextLegOrStartActivity(triggerId, tick)
              }
            case None =>
              stop(Failure(s"Expected a non-empty BeamTrip but found ${_currentRoute}"))
          }
        case _ =>
          warnAndRescheduleNotifyLeg(tick, triggerId, beamLeg, isStart = false)
      }
    case Event(TriggerWithId(NotifyLegStartTrigger(tick, beamLeg), triggerId), _) =>

      _currentEmbodiedLeg match {
        case Some(leg) =>
          // Driver is still traveling to pickup point, reschedule this trigger
          warnAndRescheduleNotifyLeg(tick, triggerId, beamLeg)
        case None =>
          stop(Failure(s"Going to Error: NotifyLegStartTrigger from state Moving but no _currentEmbodiedLeg " +
            s"defined, beamLeg: $beamLeg"))
      }
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
   * 3 The trip is over and there are no more activities in the agent plan => goto Finished
   * 4 The trip is over and there are more activities in the agent plan => goto PerformingActivity and schedule end
   * of activity
   */
  def processNextLegOrStartActivity(triggerId: Long, tick: Double): PersonAgent.this.State = {
    _currentEmbodiedLeg match {
      case Some(embodiedBeamLeg) =>
        if (embodiedBeamLeg.unbecomeDriverOnCompletion) {
          beamServices.vehicles(_currentVehicle.outermostVehicle()).unsetDriver()
          context.system.eventStream.publish(new PersonLeavesVehicleEvent(tick, id, _currentVehicle.outermostVehicle()))
          _currentVehicle = _currentVehicle.pop()
        }
      case None =>
    }
    if (_currentRoute.legs.nonEmpty) {
      breakTripIntoNextLegAndRestOfTrip(_currentRoute, tick) match {
        case Some(processedData) =>
          if (processedData.nextLeg.beamLeg.startTime < tick) {
            stop(Failure(s"I am going to schedule a leg for ${processedData.nextLeg.beamLeg.startTime}, but it is " +
              s"$tick."))
          } else if (processedData.nextLeg.asDriver) {
            val passengerSchedule = PassengerSchedule()
            val vehiclePersonId = if (HumanBodyVehicle.isHumanBodyVehicle(processedData.nextLeg.beamVehicleId)) {
              VehiclePersonId(_humanBodyVehicle, id)
            } else {
              VehiclePersonId(processedData.nextLeg.beamVehicleId, id)
            }
            //TODO the following needs to find all subsequent legs in currentRoute for which this agent is driver and
            // vehicle is the same...
            val nextEmbodiedBeamLeg = processedData.nextLeg
            passengerSchedule.addLegs(Vector(nextEmbodiedBeamLeg.beamLeg))
            holdTickAndTriggerId(tick, triggerId)
            if (!_currentVehicle.isEmpty && _currentVehicle.outermostVehicle() == vehiclePersonId.vehicleId) {
              // We are already in vehicle from before, so update schedule
              //XXXX (VR): Easy refactor => send directly to driver
              beamServices.vehicles(vehiclePersonId.vehicleId).driver.foreach(_ ! ModifyPassengerSchedule
              (passengerSchedule))
            } else {
              //XXXX (VR): Our first time entering this vehicle, so become driver directly
              val vehicle = beamServices.vehicles(vehiclePersonId.vehicleId)
              vehicle.becomeDriver(self).fold(fa =>
                stop(Failure(s"BeamAgent $self attempted to become driver of vehicle $id " +
                  s"but driver ${vehicle.driver.get} already assigned.")),
                fb => {
                  vehicle.driver.get ! BecomeDriverSuccess(Some(passengerSchedule),vehicle)
                  context.system.eventStream.publish(new PersonEntersVehicleEvent(tick, Id.createPersonId(id), vehicle.id))
                })
            }
            _currentVehicle = _currentVehicle.pushIfNew(vehiclePersonId.vehicleId)
            _currentRoute = processedData.restTrip
            _currentEmbodiedLeg = Some(processedData.nextLeg)
            stay()
          }
          else {
            // We don't update the rest of the currentRoute, this will happen when the agent recieves the
            // NotifyStartLegTrigger
            _currentEmbodiedLeg = None
            goto(Waiting) replying completed(triggerId)
          }
        case None =>
          stop(Failure(s"Expected a non-empty BeamTrip but found ${_currentRoute}"))
      }
    }
    else {
      val savedLegMode = _currentRoute.tripClassifier
      _currentEmbodiedLeg = None
      nextActivity match {
        case Left(msg) =>
          logDebug(msg)
          stop replying completed(triggerId)
        case Right(activity) =>
          _currentActivityIndex = _currentActivityIndex + 1
          currentTourPersonalVehicle match {
            case Some(personalVeh) =>
              if (currentActivity.getType.equals("Home")) {
                beamServices.householdRefs(_household) ! ReleaseVehicleReservation(id, personalVeh)
                //XXXX (VR): use resource method on vehicle
                self ! TellManagerResourceIsAvailable(new SpaceTime(activity.getCoord, tick.toLong))
                currentTourPersonalVehicle = None
              } else {
                beamServices.householdRefs(_household) ! NotifyNewVehicleLocation(personalVeh, new SpaceTime(activity
                  .getCoord, tick.toLong))
              }
            case None =>
          }
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
          context.system.eventStream.publish(new PersonArrivalEvent(tick, id, activity.getLinkId, _currentTripMode
            .get.matsimMode))
          _currentTripMode = None
          context.system.eventStream.publish(new ActivityStartEvent(tick, id, activity.getLinkId, activity
            .getFacilityId, activity.getType))
          goto(PerformingActivity) replying completed(triggerId, schedule[ActivityEndTrigger](endTime, self))
      }
    }
  }

  def cancelTrip(legsToCancel: Vector[EmbodiedBeamLeg], startingVehicle: VehicleStack): Unit = {
    if (legsToCancel.nonEmpty) {
      var inferredVehicle = startingVehicle
      var exitNextVehicle = false
      var prevLeg = legsToCancel.head

      if (inferredVehicle.nestedVehicles.nonEmpty) inferredVehicle = inferredVehicle.pop()

      for (leg <- legsToCancel) {
        if (exitNextVehicle || (!prevLeg.asDriver && leg.beamVehicleId != prevLeg.beamVehicleId)) inferredVehicle =
          inferredVehicle.pop()

        if (inferredVehicle.isEmpty || inferredVehicle.outermostVehicle() != leg.beamVehicleId) {
          inferredVehicle = inferredVehicle.pushIfNew(leg.beamVehicleId)
          if (inferredVehicle.nestedVehicles.size > 1 && !leg.asDriver && leg.beamLeg.mode.isTransit) {
            TransitDriverAgent.selectByVehicleId(inferredVehicle
              .outermostVehicle()) ! RemovePassengerFromTrip(VehiclePersonId(inferredVehicle.penultimateVehicle(), id))
          }
        }
        exitNextVehicle = leg.asDriver && leg.unbecomeDriverOnCompletion
        prevLeg = leg
      }
    }
  }

  override def postStop(): Unit = {
    val legs = _currentEmbodiedLeg ++: _currentRoute.legs
    if (legs.nonEmpty) {
      logWarn(s"Agent $id stopped. Sending RemovePassengerFromTrip request.")
      cancelTrip(legs, _currentVehicle)
    }
    super.postStop()
  }

  chainedWhen(AnyState) {
    case Event(ModifyPassengerScheduleAck(_), _) =>
      scheduleStartLegAndStay()
    case Event(BecomeDriverSuccessAck, _) =>
      scheduleStartLegAndStay()
    case Event(IllegalTriggerGoToError(reason), _) =>
      stop(Failure(reason))
    case Event(Finish, _) =>
      stop
  }

  def scheduleStartLegAndStay(): State = {
    val (tick, triggerId) = releaseTickAndTriggerId()
    val newTriggerTime = _currentEmbodiedLeg.get.beamLeg.startTime
    if (newTriggerTime < tick) {
      stop(Failure(s"It is $tick and I am trying to schedule the start of my " +
        s"leg for $newTriggerTime. I can't do that."))
    }
    beamServices.schedulerRef ! completed(triggerId, schedule[StartLegTrigger](newTriggerTime, self,
      _currentEmbodiedLeg.get.beamLeg))
    stay
  }

  override def logPrefix(): String

  = s"PersonAgent:$id "

  private def breakTripIntoNextLegAndRestOfTrip(trip: EmbodiedBeamTrip, tick: Double): Option[ProcessedData]

  = {
    if (trip.legs.isEmpty) {
      None
    } else {
      val nextLeg = trip.legs.head
      val restLegs = trip.legs.tail
      val restTrip: EmbodiedBeamTrip = EmbodiedBeamTrip(restLegs)
      val nextStart = if (restTrip.legs.nonEmpty) {
        restTrip.legs.head.beamLeg.startTime
      } else {
        tick
      }
      Some(ProcessedData(nextLeg, restTrip, nextStart))
    }
  }

  case class ProcessedData(nextLeg: EmbodiedBeamLeg, restTrip: EmbodiedBeamTrip, nextStart: Double)


}






