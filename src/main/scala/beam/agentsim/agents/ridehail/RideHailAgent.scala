package beam.agentsim.agents.ridehail

import akka.actor.FSM.Failure
import akka.actor.{ActorRef, Props, Stash}
import beam.agentsim.Resource.CheckInResource
import beam.agentsim.ResourceManager.NotifyVehicleResourceIdle
import beam.agentsim.agents.BeamAgent._
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.agents.modalbehaviors.DrivesVehicle
import beam.agentsim.agents.modalbehaviors.DrivesVehicle.{
  EndLegTrigger,
  EndRefuelTrigger,
  StartLegTrigger,
  StartRefuelTrigger
}
import beam.agentsim.agents.ridehail.RideHailAgent._
import beam.agentsim.agents.vehicles.VehicleProtocol.{
  BecomeDriverOfVehicleSuccess,
  DriverAlreadyAssigned,
  NewDriverAlreadyControllingVehicle
}
import beam.agentsim.agents.vehicles.{BeamVehicle, PassengerSchedule}
import beam.agentsim.agents.{BeamAgent, InitializeTrigger}
import beam.agentsim.events.{RefuelEvent, SpaceTime}
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, IllegalTriggerGoToError, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.router.model.{EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.router.model.RoutingModel
import beam.router.osm.TollCalculator
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
    tollCalculator: TollCalculator,
    eventsManager: EventsManager,
    parkingManager: ActorRef,
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
        parkingManager,
        services,
        transportNetwork,
        tollCalculator
      )
    )

  def getRideHailTrip(chosenTrip: EmbodiedBeamTrip): IndexedSeq[EmbodiedBeamLeg] = {
    chosenTrip.legs.filter(l => isRideHailLeg(l))
  }

  def isRideHailLeg(currentLeg: EmbodiedBeamLeg): Boolean = {
    currentLeg.beamVehicleId.toString.contains("rideHailVehicle")
  }

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

  // triggerId is included to facilitate debugging
  case class NotifyVehicleResourceIdleReply(
    triggerId: Option[Long],
    newTriggers: Seq[ScheduleTrigger]
  )

  case class ModifyPassengerSchedule(
    updatedPassengerSchedule: PassengerSchedule,
    reservationRequestId: Option[Int] = None
  )

  case class ModifyPassengerScheduleAck(
    reservationRequestId: Option[Int] = None,
    triggersToSchedule: Vector[ScheduleTrigger],
    vehicleId: Id[Vehicle]
  )

  case class Interrupt(interruptId: Id[Interrupt], tick: Double)

  case class Resume()

  sealed trait InterruptReply {
    val interruptId: Id[Interrupt]
    val vehicleId: Id[Vehicle]
    val tick: Double
  }

  case class InterruptedWhileDriving(
    interruptId: Id[Interrupt],
    vehicleId: Id[Vehicle],
    tick: Double,
    passengerSchedule: PassengerSchedule,
    currentPassengerScheduleIndex: Int,
  ) extends InterruptReply

  case class InterruptedWhileIdle(interruptId: Id[Interrupt], vehicleId: Id[Vehicle], tick: Double)
      extends InterruptReply

  case object Idle extends BeamAgentState

  case object IdleInterrupted extends BeamAgentState

}

class RideHailAgent(
  override val id: Id[RideHailAgent],
  val scheduler: ActorRef,
  vehicle: BeamVehicle,
  initialLocation: Coord,
  val eventsManager: EventsManager,
  val parkingManager: ActorRef,
  val beamServices: BeamServices,
  val transportNetwork: TransportNetwork,
  val tollCalculator: TollCalculator
) extends BeamAgent[RideHailAgentData]
    with DrivesVehicle[RideHailAgentData]
    with Stash {

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
      log.error(
        "unhandled event: {} in state [ {} ] - vehicle( {} )",
        event.toString,
        stateName,
        vehicle.id.toString
      )
      stay()

  }

  override def logDepth: Int = beamServices.beamConfig.beam.debug.actor.logDepth

  startWith(Uninitialized, RideHailAgentData())

  when(Uninitialized) {
    case Event(TriggerWithId(InitializeTrigger(tick), triggerId), data) =>
      vehicle
        .becomeDriver(self) match {
        case DriverAlreadyAssigned(_) =>
          stop(
            Failure(
              s"RideHailAgent $self attempted to become driver of vehicle ${vehicle.id} " +
              s"but driver ${vehicle.driver.get} already assigned."
            )
          )
        case NewDriverAlreadyControllingVehicle | BecomeDriverOfVehicleSuccess =>
          vehicle.checkInResource(Some(SpaceTime(initialLocation, tick)), context.dispatcher)
          eventsManager.processEvent(
            new PersonDepartureEvent(tick, Id.createPersonId(id), Id.createLinkId(""), "be_a_tnc_driver")
          )
          eventsManager.processEvent(new PersonEntersVehicleEvent(tick, Id.createPersonId(id), vehicle.id))
          goto(Idle) replying CompletionNotice(triggerId) using data
            .copy(currentVehicle = Vector(vehicle.id))
      }
  }

  when(Idle) {
    case ev @ Event(Interrupt(interruptId: Id[Interrupt], tick), _) =>
      log.debug("state(RideHailingAgent.Idle): {}", ev)
      goto(IdleInterrupted) replying InterruptedWhileIdle(interruptId, vehicle.id, tick)
    case ev @ Event(
          NotifyVehicleResourceIdleReply(
            triggerId: Option[Long],
            newTriggers: Seq[ScheduleTrigger]
          ),
          _
        ) =>
      log.debug("state(RideHailingAgent.Idle.NotifyVehicleResourceIdleReply): {}", ev)
      handleNotifyVehicleResourceIdleReply(triggerId, newTriggers)
    case ev @ Event(
          TriggerWithId(EndRefuelTrigger(tick, sessionStart, energyInJoules), triggerId),
          data
        ) =>
      log.debug("state(RideHailingAgent.Idle.EndRefuelTrigger): {}", ev)
      holdTickAndTriggerId(tick, triggerId)
      data.currentVehicle.headOption match {
        case Some(currentVehicleUnderControl) =>
          val theVehicle = beamServices.vehicles(currentVehicleUnderControl)
          log.debug("Ending refuel session for {}", theVehicle.id)
          theVehicle.addFuel(energyInJoules)
          eventsManager.processEvent(
            new RefuelEvent(
              tick,
              theVehicle.stall.get.copy(location = beamServices.geo.utm2Wgs(theVehicle.stall.get.location)),
              energyInJoules,
              tick - sessionStart,
              theVehicle.id
            )
          )
          parkingManager ! CheckInResource(theVehicle.stall.get.id, None)
          val whenWhere = Some(SpaceTime(theVehicle.stall.get.location, tick))
          theVehicle.unsetParkingStall()
          theVehicle.manager.foreach(
            _ ! NotifyVehicleResourceIdle(
              currentVehicleUnderControl,
              whenWhere,
              data.passengerSchedule,
              theVehicle.getState,
              _currentTriggerId
            )
          )
          stay()
        case None =>
          log.debug("currentVehicleUnderControl not found")
          stay() replying CompletionNotice(triggerId, Vector())
      }
    case ev @ Event(TriggerWithId(StartRefuelTrigger(tick), triggerId), data) =>
      log.debug("state(RideHailingAgent.Idle.StartRefuelTrigger): {}", ev)
      data.currentVehicle.headOption match {
        case Some(currentVehicleUnderControl) =>
          val theVehicle = beamServices.vehicles(currentVehicleUnderControl)
          //          theVehicle.useParkingStall(stall)
          val (sessionDuration, energyDelivered) =
            theVehicle.refuelingSessionDurationAndEnergyInJoules()

          log.debug(
            "scheduling EndRefuelTrigger at {} with {} J to be delivered",
            tick + sessionDuration.toInt,
            energyDelivered
          )
          stay() replying CompletionNotice(
            triggerId,
            Vector(
              ScheduleTrigger(EndRefuelTrigger(tick + sessionDuration.toInt, tick, energyDelivered), self)
            )
          )
        case None =>
          log.debug("currentVehicleUnderControl not found")
          stay()
      }
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
      if (updatedPassengerSchedule.schedule.firstKey.startTime == 24600) {
        val i = 0
      }
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
    case ev @ Event(
          NotifyVehicleResourceIdleReply(
            triggerId: Option[Long],
            newTriggers: Seq[ScheduleTrigger]
          ),
          _
        ) =>
      log.debug("state(RideHailingAgent.IdleInterrupted.NotifyVehicleResourceIdleReply): {}", ev)
      handleNotifyVehicleResourceIdleReply(triggerId, newTriggers)
  }

  when(PassengerScheduleEmpty) {
    case ev @ Event(PassengerScheduleEmptyMessage(_, _), data) =>
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
    case ev @ Event(PassengerScheduleEmptyMessage(_, _), data) =>
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

  override def logPrefix(): String = s"RideHailAgent $id: "

  def handleNotifyVehicleResourceIdleReply(
    receivedtriggerId: Option[Long],
    newTriggers: Seq[ScheduleTrigger]
  ): State = {
    _currentTriggerId match {
      case Some(_) =>
        val (_, triggerId) = releaseTickAndTriggerId()
        if (receivedtriggerId.isEmpty || triggerId != receivedtriggerId.get) {
          log.error(
            "RHA {}: local triggerId {} does not match the id received from RHM {}",
            id,
            triggerId,
            receivedtriggerId
          )
        }
        log.debug("RHA {}: completing trigger and scheduling {}", id, newTriggers)
        scheduler ! CompletionNotice(triggerId, newTriggers)
      case None =>
        log.error("RHA {}: was expecting to release a triggerId but None found", id)
    }
    stay()
  }

  whenUnhandled(drivingBehavior.orElse(myUnhandled))

  onTransition {
    case _ -> Idle =>
      unstashAll()

      nextNotifyVehicleResourceIdle match {

        case Some(nextIdle) =>
          stateData.currentVehicle.headOption match {
            case Some(currentVehicleUnderControl) =>
              val theVehicle = beamServices.vehicles(currentVehicleUnderControl)

              _currentTriggerId.foreach(
                log.debug(
                  "state(RideHailingAgent.awaiting NotifyVehicleResourceIdleReply) - triggerId: {}",
                  _
                )
              )

              if (_currentTriggerId != nextIdle.triggerId) {
                log.error(
                  "_currentTriggerId({}) and nextNotifyVehicleResourceIdle.triggerId({}) don't match - vehicleId({})",
                  _currentTriggerId,
                  nextIdle.triggerId,
                  currentVehicleUnderControl
                )
                //assert(false)
              }

              theVehicle.manager.foreach(
                _ ! nextIdle
              )
            case None =>
          }

        case None =>
      }

      nextNotifyVehicleResourceIdle = None

    case _ -> _ =>
      unstashAll()

  }

}
