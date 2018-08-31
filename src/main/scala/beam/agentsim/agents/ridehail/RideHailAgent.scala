package beam.agentsim.agents.ridehail

import akka.actor.FSM.Failure
import akka.actor.{ActorRef, Props, Stash}
import beam.agentsim.ResourceManager.NotifyVehicleResourceIdle
import beam.agentsim.agents.BeamAgent._
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.agents.modalbehaviors.DrivesVehicle
import beam.agentsim.agents.modalbehaviors.DrivesVehicle.{EndLegTrigger, StartLegTrigger}
import beam.agentsim.agents.ridehail.RideHailAgent._
import beam.agentsim.agents.vehicles.{BeamVehicle, PassengerSchedule}
import beam.agentsim.agents.{BeamAgent, InitializeTrigger}
import beam.agentsim.events.SpaceTime
import beam.agentsim.scheduler.BeamAgentScheduler.{
  CompletionNotice,
  IllegalTriggerGoToError,
  ScheduleTrigger
}
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.router.RoutingModel
import beam.router.RoutingModel.{EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.sim.BeamServices
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.api.core.v01.events.{PersonDepartureEvent, PersonEntersVehicleEvent}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.vehicles.Vehicle

object RideHailAgent {
  val idPrefix: String = "rideHailAgent"

  def props(
    services: BeamServices,
    scheduler: ActorRef,
    transportNetwork: TransportNetwork,
    eventsManager: EventsManager,
    rideHailAgentId: Id[RideHailAgent],
    vehicle: BeamVehicle,
    location: Coord
  ) =
    Props(
      new RideHailAgent(
        rideHailAgentId,
        scheduler,
        vehicle,
        location,
        eventsManager,
        services,
        transportNetwork
      )
    )

  case class RideHailAgentData(
    currentVehicle: VehicleStack = Vector(),
    passengerSchedule: PassengerSchedule = PassengerSchedule(),
    currentLegPassengerScheduleIndex: Int = 0
  ) extends DrivingData {
    override def withPassengerSchedule(newPassengerSchedule: PassengerSchedule): DrivingData =
      copy(passengerSchedule = newPassengerSchedule)

    override def withCurrentLegPassengerScheduleIndex(
      currentLegPassengerScheduleIndex: Int
    ): DrivingData = copy(currentLegPassengerScheduleIndex = currentLegPassengerScheduleIndex)

    override def hasParkingBehaviors: Boolean = false
  }

  def isRideHailLeg(currentLeg: EmbodiedBeamLeg): Boolean = {
    currentLeg.beamVehicleId.toString.contains("rideHailVehicle")
  }

  def getRideHailTrip(chosenTrip: EmbodiedBeamTrip): IndexedSeq[RoutingModel.EmbodiedBeamLeg] = {
    chosenTrip.legs.filter(l => isRideHailLeg(l))
  }

  case object Idle extends BeamAgentState

  case object IdleInterrupted extends BeamAgentState

  case class NotifyVehicleResourceIdleReply(newTriggers: Seq[ScheduleTrigger])

  case class ModifyPassengerSchedule(
    updatedPassengerSchedule: PassengerSchedule,
    msgId: Option[java.util.UUID] = None
  )

  case class ModifyPassengerScheduleAck(
    msgId: Option[java.util.UUID] = None,
    triggersToSchedule: Seq[ScheduleTrigger],
    vehicleId: Id[Vehicle]
  )

  case class Interrupt(interruptId: Id[Interrupt], tick: Double)

  case class Resume()

  case class InterruptedAt(
    interruptId: Id[Interrupt],
    passengerSchedule: PassengerSchedule,
    currentPassengerScheduleIndex: Int,
    vehicleId: Id[Vehicle],
    tick: Double
  )

  case class InterruptedWhileIdle(interruptId: Id[Interrupt], vehicleId: Id[Vehicle], tick: Double)

}

class RideHailAgent(
  override val id: Id[RideHailAgent],
  val scheduler: ActorRef,
  vehicle: BeamVehicle,
  initialLocation: Coord,
  val eventsManager: EventsManager,
  val beamServices: BeamServices,
  val transportNetwork: TransportNetwork
) extends BeamAgent[RideHailAgentData]
    with DrivesVehicle[RideHailAgentData]
    with Stash {

  override def logDepth: Int = beamServices.beamConfig.beam.debug.actor.logDepth

  override def logPrefix(): String = s"RideHailAgent $id: "

  startWith(Uninitialized, RideHailAgentData())

  when(Uninitialized) {
    case Event(TriggerWithId(InitializeTrigger(tick), triggerId), data) =>
      vehicle
        .becomeDriver(self)
        .fold(
          _ =>
            stop(
              Failure(
                s"RideHailAgent $self attempted to become driver of vehicle ${vehicle.id} " +
                s"but driver ${vehicle.driver.get} already assigned."
              )
          ),
          _ => {
            vehicle
              .checkInResource(Some(SpaceTime(initialLocation, tick.toLong)), context.dispatcher)
            eventsManager.processEvent(
              new PersonDepartureEvent(tick, Id.createPersonId(id), null, "be_a_tnc_driver")
            )
            eventsManager
              .processEvent(new PersonEntersVehicleEvent(tick, Id.createPersonId(id), vehicle.id))
            goto(Idle) replying CompletionNotice(triggerId) using data
              .copy(currentVehicle = Vector(vehicle.id))
          }
        )
  }

  when(Idle) {
    case ev @ Event(Interrupt(interruptId: Id[Interrupt], tick), _) =>
      log.debug("state(RideHailingAgent.Idle): {}", ev)
      goto(IdleInterrupted) replying InterruptedWhileIdle(interruptId, vehicle.id, tick)
    case ev @ Event(NotifyVehicleResourceIdleReply(newTriggers: Seq[ScheduleTrigger]), _) =>
      log.debug("state(RideHailingAgent.Idle.NotifyVehicleResourceIdleReply): {}", ev)
      handleNotifyVehicleResourceIdleReply(newTriggers)
  }

  when(IdleInterrupted) {
    case ev @ Event(ModifyPassengerSchedule(updatedPassengerSchedule, requestId), data) =>
      log.debug("state(RideHailingAgent.IdleInterrupted): {}", ev)
      // This is a message from another agent, the ride-hailing manager. It is responsible for "keeping the trigger",
      // i.e. for what time it is. For now, we just believe it that time is not running backwards.
      log.debug("updating Passenger schedule - vehicleId({}): {}", id, updatedPassengerSchedule)
      val triggerToSchedule = Vector(
        ScheduleTrigger(
          StartLegTrigger(
            updatedPassengerSchedule.schedule.firstKey.startTime,
            updatedPassengerSchedule.schedule.firstKey
          ),
          self
        )
      )
      goto(WaitingToDriveInterrupted) using data
        .withPassengerSchedule(updatedPassengerSchedule)
        .asInstanceOf[RideHailAgentData] replying ModifyPassengerScheduleAck(
        requestId,
        triggerToSchedule,
        vehicle.id
      )
    case ev @ Event(Resume(), _) =>
      log.debug("state(RideHailingAgent.IdleInterrupted): {}", ev)
      goto(Idle)
    case ev @ Event(Interrupt(interruptId: Id[Interrupt], tick), _) =>
      log.debug("state(RideHailingAgent.IdleInterrupted): {}", ev)
      stay() replying InterruptedWhileIdle(interruptId, vehicle.id, tick)
    case ev @ Event(NotifyVehicleResourceIdleReply(newTriggers: Seq[ScheduleTrigger]), _) =>
      log.debug("state(RideHailingAgent.IdleInterrupted.NotifyVehicleResourceIdleReply): {}", ev)
      handleNotifyVehicleResourceIdleReply(newTriggers)
  }

  when(PassengerScheduleEmpty) {
    case ev @ Event(PassengerScheduleEmptyMessage(_), data) =>
      log.debug("state(RideHailingAgent.PassengerScheduleEmpty): {}", ev)
      goto(Idle) using data
        .withPassengerSchedule(PassengerSchedule())
        .withCurrentLegPassengerScheduleIndex(0)
        .asInstanceOf[RideHailAgentData]
    case ev @ Event(Interrupt(_, _), _) =>
      log.debug("state(RideHailingAgent.PassengerScheduleEmpty): {}", ev)
      stash()
      stay()
  }

  when(PassengerScheduleEmptyInterrupted) {
    case ev @ Event(PassengerScheduleEmptyMessage(_), data) =>
      log.debug("state(RideHailingAgent.PassengerScheduleEmptyInterrupted): {}", ev)
      goto(IdleInterrupted) using data
        .withPassengerSchedule(PassengerSchedule())
        .withCurrentLegPassengerScheduleIndex(0)
        .asInstanceOf[RideHailAgentData]
    case ev @ Event(ModifyPassengerSchedule(_, _), _) =>
      log.debug("state(RideHailingAgent.PassengerScheduleEmptyInterrupted): {}", ev)
      stash()
      stay()
    case ev @ Event(Resume(), _) =>
      log.debug("state(RideHailingAgent.PassengerScheduleEmptyInterrupted): {}", ev)
      stash()
      stay()
    case ev @ Event(Interrupt(_, _), _) =>
      log.debug("state(RideHailingAgent.PassengerScheduleEmptyInterrupted): {}", ev)
      stash()
      stay()
  }

  val myUnhandled: StateFunction = {

    case ev @ Event(TriggerWithId(EndLegTrigger(_), triggerId), _) =>
      log.debug("state(RideHailingAgent.myUnhandled): {}", ev)
      stay replying CompletionNotice(triggerId)

    case ev @ Event(IllegalTriggerGoToError(reason), _) =>
      log.debug("state(RideHailingAgent.myUnhandled): {}", ev)
      stop(Failure(reason))

    case ev @ Event(Finish, _) =>
      log.debug("state(RideHailingAgent.myUnhandled): {}", ev)
      stop

    case event @ Event(_, _) =>
      log.warning(
        s"unhandled event: " + event.toString + "in state [" + stateName + "] - vehicle(" + vehicle.id.toString + ")"
      )
      stay()

  }

  def handleNotifyVehicleResourceIdleReply(newTriggers: Seq[ScheduleTrigger]): State = {
    _currentTriggerId match {
      case Some(_) =>
        val (_, triggerId) = releaseTickAndTriggerId()
        scheduler ! CompletionNotice(triggerId, newTriggers)
      case None =>
    }

    stay()
  }

  whenUnhandled(drivingBehavior.orElse(myUnhandled))

  onTransition {
    case _ -> Idle =>
      unstashAll()

      nextNotifyVehicleResourceIdle match {

        case Some(nextNotifyVehicleResourceIdle) =>
          stateData.currentVehicle.headOption match {
            case Some(currentVehicleUnderControl) =>
              val theVehicle = beamServices.vehicles(currentVehicleUnderControl)

              theVehicle.manager.foreach(
                _ ! nextNotifyVehicleResourceIdle
              )
          }

        case None =>
      }

      nextNotifyVehicleResourceIdle = None

    case _ -> _ =>
      unstashAll()

  }

}
