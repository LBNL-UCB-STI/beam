package beam.agentsim.agents.rideHail

import akka.actor.FSM.Failure
import akka.actor.{ActorRef, Props, Stash}
import beam.agentsim.agents.BeamAgent._
import beam.agentsim.agents.PersonAgent.{DrivingData, PassengerScheduleEmpty, VehicleStack, WaitingToDrive}
import beam.agentsim.agents.modalBehaviors.DrivesVehicle
import beam.agentsim.agents.modalBehaviors.DrivesVehicle.StartLegTrigger
import beam.agentsim.agents.rideHail.RideHailingAgent._
import beam.agentsim.agents.vehicles.{BeamVehicle, PassengerSchedule}
import beam.agentsim.agents.{BeamAgent, InitializeTrigger}
import beam.agentsim.events.SpaceTime
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, IllegalTriggerGoToError, ScheduleTrigger}
import beam.agentsim.scheduler.TriggerWithId
import beam.router.RoutingModel
import beam.router.RoutingModel.{EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.sim.BeamServices
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.api.core.v01.events.{PersonDepartureEvent, PersonEntersVehicleEvent}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.api.experimental.events.EventsManager

object RideHailingAgent {
  val idPrefix: String = "rideHailingAgent"

  def props(services: BeamServices, scheduler: ActorRef, transportNetwork: TransportNetwork, eventsManager: EventsManager, rideHailingAgentId: Id[RideHailingAgent], vehicle: BeamVehicle, location: Coord) =
    Props(new RideHailingAgent(rideHailingAgentId, scheduler, vehicle, location, eventsManager, services, transportNetwork))

  case class RideHailingAgentData(currentVehicle: VehicleStack = Vector(), stashedPassengerSchedules: List[PassengerSchedule] = List(), passengerSchedule: PassengerSchedule = PassengerSchedule(), currentLegPassengerScheduleIndex: Int = 0) extends DrivingData {
    override def withPassengerSchedule(newPassengerSchedule: PassengerSchedule): DrivingData = copy(passengerSchedule = newPassengerSchedule)
    override def withCurrentLegPassengerScheduleIndex(currentLegPassengerScheduleIndex: Int): DrivingData = copy(currentLegPassengerScheduleIndex = currentLegPassengerScheduleIndex)
  }

  def isRideHailingLeg(currentLeg: EmbodiedBeamLeg): Boolean = {
    currentLeg.beamVehicleId.toString.contains("rideHailingVehicle")
  }

  def getRideHailingTrip(chosenTrip: EmbodiedBeamTrip): Vector[RoutingModel.EmbodiedBeamLeg] = {
    chosenTrip.legs.filter(l => isRideHailingLeg(l))
  }

  case object Idle extends BeamAgentState
  case object Interrupted extends BeamAgentState

  case class ModifyPassengerSchedule(updatedPassengerSchedule: PassengerSchedule, msgId: Option[Id[_]] = None)

  case class ModifyPassengerScheduleAck(msgId: Option[Id[_]] = None, triggersToSchedule: Seq[ScheduleTrigger])

  case class Interrupt()
  case class Resume()
  case class InterruptedAt(tick: Double)

}

class RideHailingAgent(override val id: Id[RideHailingAgent], val scheduler: ActorRef, vehicle: BeamVehicle, initialLocation: Coord,
                       val eventsManager: EventsManager, val beamServices: BeamServices, val transportNetwork: TransportNetwork)
  extends BeamAgent[RideHailingAgentData] with DrivesVehicle[RideHailingAgentData] with Stash {
  override def logPrefix(): String = s"RideHailingAgent $id: "

  startWith(Uninitialized, RideHailingAgentData())

  var lastTick = 0.0
  var lastStateName: BeamAgentState = _


  when(Uninitialized) {
    case Event(TriggerWithId(InitializeTrigger(tick), triggerId), data) =>
      vehicle.becomeDriver(self).fold(fa =>
        stop(Failure(s"RideHailingAgent $self attempted to become driver of vehicle ${vehicle.id} " +
          s"but driver ${vehicle.driver.get} already assigned.")), fb => {
        vehicle.checkInResource(Some(SpaceTime(initialLocation,tick.toLong)),context.dispatcher)
        eventsManager.processEvent(new PersonDepartureEvent(tick, Id.createPersonId(id), null, "be_a_tnc_driver"))
        eventsManager.processEvent(new PersonEntersVehicleEvent(tick, Id.createPersonId(id), vehicle.id))
        lastTick = tick
        goto(Idle) replying CompletionNotice(triggerId) using data.copy(currentVehicle = Vector(vehicle.id))
      })
  }

  when(Idle) {
    case Event(ModifyPassengerSchedule(updatedPassengerSchedule, requestId), data) =>
      // This is a message from another agent, the ride-hailing manager. It is responsible for "keeping the trigger",
      // i.e. for what time it is. For now, we just believe it that time is not running backwards.
      // TODO: Keep a local time, i.e. the time of the latest trigger we ourselves received, and make sure that at least
      // TODO: our local time does not run backwards, i.e. let this leg here start no earlier than the latest trigger we received.
      val triggerToSchedule = Vector(ScheduleTrigger(StartLegTrigger(updatedPassengerSchedule.schedule.firstKey.startTime, updatedPassengerSchedule.schedule.firstKey), self))
      goto(WaitingToDrive) using data.withPassengerSchedule(updatedPassengerSchedule).asInstanceOf[RideHailingAgentData] replying ModifyPassengerScheduleAck(requestId, triggerToSchedule)
  }

  when(PassengerScheduleEmpty) {
    case Event(PassengerScheduleEmptyMessage(lastVisited), data) if data.stashedPassengerSchedules.isEmpty =>
      val (tick, triggerId) = releaseTickAndTriggerId()
      vehicle.checkInResource(Some(lastVisited),context.dispatcher)
      scheduler ! CompletionNotice(triggerId)
      lastTick = tick
      goto(Idle) using data.withPassengerSchedule(PassengerSchedule()).withCurrentLegPassengerScheduleIndex(0).asInstanceOf[RideHailingAgentData]

    case Event(PassengerScheduleEmptyMessage(lastVisited), data@RideHailingAgentData(_, nextSchedule::restOfSchedules, _, _)) =>
      val (tick, triggerId) = releaseTickAndTriggerId()
      vehicle.checkInResource(Some(lastVisited),context.dispatcher) // ??
      val startTime = math.max(tick, nextSchedule.schedule.firstKey.startTime)
      val triggerToSchedule = Vector(ScheduleTrigger(StartLegTrigger(startTime, nextSchedule.schedule.firstKey), self))
      scheduler ! CompletionNotice(triggerId, triggerToSchedule)
      lastTick = tick
      goto(WaitingToDrive) using data.copy(stashedPassengerSchedules = restOfSchedules).withPassengerSchedule(nextSchedule).withCurrentLegPassengerScheduleIndex(0).asInstanceOf[RideHailingAgentData]
  }

  when(Interrupted) {
    case Event(Resume(), _) =>
      unstashAll()
      goto(lastStateName)

    case _ =>
      stash()
      stay
  }


  val myUnhandled: StateFunction =  {
    case Event(ModifyPassengerSchedule(updatedPassengerSchedule, requestId), data) =>
      // Ride-hailing manager wants us to drive,
      // but we are not Idle.
      // Not being Idle means we cannot return a trigger to be scheduled to start driving.
      // Rather, we stash a reminder to start driving
      // as soon as we finish our current trip.
      stay using data.copy(stashedPassengerSchedules = data.stashedPassengerSchedules :+ updatedPassengerSchedule) replying ModifyPassengerScheduleAck(requestId, Vector())

    case Event(IllegalTriggerGoToError(reason), _) =>
      stop(Failure(reason))

    case Event(Interrupt(), _) =>
      log.debug("Interrupted.")
      lastStateName = stateName
      goto(Interrupted) replying InterruptedAt(lastTick)

    case Event(Finish, _) =>
      stop
  }

  whenUnhandled(drivingBehavior.orElse(myUnhandled))

  onTransition {
    case _ -> _ =>
      unstashAll()
  }

  onTransition {
    case _ -> _ =>

      println("pups")

  }


}


