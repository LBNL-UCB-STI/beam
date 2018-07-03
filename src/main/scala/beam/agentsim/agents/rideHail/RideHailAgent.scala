package beam.agentsim.agents.rideHail

import akka.actor.FSM.Failure
import akka.actor.{ActorRef, Props, Stash}
import beam.agentsim.agents.BeamAgent._
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.agents.modalBehaviors.DrivesVehicle
import beam.agentsim.agents.modalBehaviors.DrivesVehicle.{EndLegTrigger, StartLegTrigger}
import beam.agentsim.agents.rideHail.RideHailAgent._
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
import org.matsim.vehicles.Vehicle

object RideHailAgent {
  val idPrefix: String = "rideHailingAgent"

  def props(services: BeamServices, scheduler: ActorRef, transportNetwork: TransportNetwork, eventsManager: EventsManager, rideHailAgentId: Id[RideHailAgent], vehicle: BeamVehicle, location: Coord) =
    Props(new RideHailAgent(rideHailAgentId, scheduler, vehicle, location, eventsManager, services, transportNetwork))

  case class RideHailAgentData(currentVehicle: VehicleStack = Vector(), passengerSchedule: PassengerSchedule = PassengerSchedule(), currentLegPassengerScheduleIndex: Int = 0) extends DrivingData {
    override def withPassengerSchedule(newPassengerSchedule: PassengerSchedule): DrivingData = copy(passengerSchedule = newPassengerSchedule)
    override def withCurrentLegPassengerScheduleIndex(currentLegPassengerScheduleIndex: Int): DrivingData = copy(currentLegPassengerScheduleIndex = currentLegPassengerScheduleIndex)
  }

  def isRideHailLeg(currentLeg: EmbodiedBeamLeg): Boolean = {
    currentLeg.beamVehicleId.toString.contains("rideHailingVehicle")
  }

  def getRideHailTrip(chosenTrip: EmbodiedBeamTrip): Seq[RoutingModel.EmbodiedBeamLeg] = {
    chosenTrip.legs.filter(l => isRideHailLeg(l))
  }

  case object Idle extends BeamAgentState
  case object IdleInterrupted extends BeamAgentState

  case class ModifyPassengerSchedule(updatedPassengerSchedule: PassengerSchedule, msgId: Option[Id[_]] = None)

  case class ModifyPassengerScheduleAck(msgId: Option[Id[_]] = None, triggersToSchedule: Seq[ScheduleTrigger], vehicleId:Id[Vehicle])

  case class Interrupt(interruptId: Id[Interrupt], tick: Double)
  case class Resume()

  case class InterruptedAt(interruptId: Id[Interrupt],passengerSchedule: PassengerSchedule, currentPassengerScheduleIndex: Int,vehicleId:Id[Vehicle], tick: Double)
  case class InterruptedWhileIdle(interruptId: Id[Interrupt],vehicleId:Id[Vehicle],tick: Double)

}

class RideHailAgent(override val id: Id[RideHailAgent], val scheduler: ActorRef, vehicle: BeamVehicle, initialLocation: Coord,
                    val eventsManager: EventsManager, val beamServices: BeamServices, val transportNetwork: TransportNetwork)
  extends BeamAgent[RideHailAgentData] with DrivesVehicle[RideHailAgentData] with Stash {
  override def logPrefix(): String = s"RideHailAgent $id: "

  startWith(Uninitialized, RideHailAgentData())

  when(Uninitialized) {
    case Event(TriggerWithId(InitializeTrigger(tick), triggerId), data) =>
      vehicle.becomeDriver(self).fold(_ =>
        stop(Failure(s"RideHailAgent $self attempted to become driver of vehicle ${vehicle.id} " +
          s"but driver ${vehicle.driver.get} already assigned.")), _ => {
        vehicle.checkInResource(Some(SpaceTime(initialLocation,tick.toLong)),context.dispatcher)
        eventsManager.processEvent(new PersonDepartureEvent(tick, Id.createPersonId(id), null, "be_a_tnc_driver"))
        eventsManager.processEvent(new PersonEntersVehicleEvent(tick, Id.createPersonId(id), vehicle.id))
        goto(Idle) replying CompletionNotice(triggerId) using data.copy(currentVehicle = Vector(vehicle.id))
      })
  }

  when(Idle) {
    case Event(Interrupt(interruptId: Id[Interrupt], tick), _) =>
      goto(IdleInterrupted) replying InterruptedWhileIdle(interruptId,vehicle.id,tick)
  }

  when(IdleInterrupted) {
    case Event(ModifyPassengerSchedule(updatedPassengerSchedule, requestId), data) =>
      // This is a message from another agent, the ride-hailing manager. It is responsible for "keeping the trigger",
      // i.e. for what time it is. For now, we just believe it that time is not running backwards.
      val triggerToSchedule = Vector(ScheduleTrigger(StartLegTrigger(updatedPassengerSchedule.schedule.firstKey.startTime, updatedPassengerSchedule.schedule.firstKey), self))
      goto(WaitingToDriveInterrupted) using data.withPassengerSchedule(updatedPassengerSchedule).asInstanceOf[RideHailAgentData] replying ModifyPassengerScheduleAck(requestId, triggerToSchedule,vehicle.id)
    case Event(Resume(), _) =>
      goto(Idle)
    case Event(Interrupt(interruptId: Id[Interrupt], tick), _) =>
      stay() replying InterruptedWhileIdle(interruptId,vehicle.id,tick)
  }

  when(PassengerScheduleEmpty) {
    case Event(PassengerScheduleEmptyMessage(lastVisited), data) =>
      val (_, triggerId) = releaseTickAndTriggerId()
      vehicle.checkInResource(Some(lastVisited),context.dispatcher)
      scheduler ! CompletionNotice(triggerId)
      goto(Idle) using data.withPassengerSchedule(PassengerSchedule()).withCurrentLegPassengerScheduleIndex(0).asInstanceOf[RideHailAgentData]
    case Event(Interrupt(_,_), _) =>
      stash()
      stay()
  }

  when(PassengerScheduleEmptyInterrupted) {
    case Event(PassengerScheduleEmptyMessage(lastVisited), data) =>
      vehicle.checkInResource(Some(lastVisited),context.dispatcher)
      goto(IdleInterrupted) using data.withPassengerSchedule(PassengerSchedule()).withCurrentLegPassengerScheduleIndex(0).asInstanceOf[RideHailAgentData]
    case Event(ModifyPassengerSchedule(_, _), _) =>
      stash()
      stay()
    case Event(Resume(), _) =>
      stash()
      stay()


  }

  val myUnhandled: StateFunction =  {

    case Event(TriggerWithId(EndLegTrigger(_), triggerId), _) =>
      stay replying CompletionNotice(triggerId)

    case Event(IllegalTriggerGoToError(reason), _) =>
      stop(Failure(reason))

    case Event(Finish, _) =>
      stop

    case event@Event(_,_) =>
      log.warning("unhandled event: " + event.toString + "in state [" + stateName + "] - vehicle("  + vehicle.id.toString + ")")
      stay()
  }

  whenUnhandled(drivingBehavior.orElse(myUnhandled))

  onTransition {
    case _ -> _ =>
      unstashAll()
  }

}


