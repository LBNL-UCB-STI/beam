package beam.agentsim.agents.ridehail

import akka.actor.FSM.Failure
import akka.actor.{ActorRef, Props, Stash}
import beam.agentsim.Resource.{NotifyVehicleIdle, NotifyVehicleOutOfService, ReleaseParkingStall}
import beam.agentsim.agents.BeamAgent._
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.agents.modalbehaviors.DrivesVehicle
import beam.agentsim.agents.modalbehaviors.DrivesVehicle._
import beam.agentsim.agents.ridehail.RideHailAgent._
import beam.agentsim.agents.vehicles.{BeamVehicle, PassengerSchedule}
import beam.agentsim.agents.{BeamAgent, InitializeTrigger}
import beam.agentsim.events.{RefuelEvent, SpaceTime}
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, IllegalTriggerGoToError, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.router.model.{EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.router.osm.TollCalculator
import beam.sim.common.Range
import beam.sim.{BeamServices, Geofence}
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
    rideHailManager: ActorRef,
    vehicle: BeamVehicle,
    location: Coord,
    shifts: Option[List[Range]],
    geofence: Option[Geofence]
  ) =
    Props(
      new RideHailAgent(
        rideHailAgentId,
        rideHailManager,
        scheduler,
        vehicle,
        location,
        shifts,
        geofence,
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
    currentVehicleToken: BeamVehicle,
    currentVehicle: VehicleStack = Vector(),
    passengerSchedule: PassengerSchedule = PassengerSchedule(),
    currentLegPassengerScheduleIndex: Int = 0,
    remainingShifts: List[Range] = List()
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
    tick: Int,
    reservationRequestId: Option[Int] = None
  )

  case class ModifyPassengerScheduleAck(
    reservationRequestId: Option[Int] = None,
    triggersToSchedule: Vector[ScheduleTrigger],
    vehicleId: Id[Vehicle],
    tick: Int
  )
  case class ModifyPassengerScheduleAcks(acks: List[ModifyPassengerScheduleAck])

  case class Interrupt(interruptId: Id[Interrupt], tick: Int)

  case class Resume()

  sealed trait InterruptReply {
    val interruptId: Id[Interrupt]
    val vehicleId: Id[Vehicle]
    val tick: Int
  }

  case class InterruptedWhileDriving(
    interruptId: Id[Interrupt],
    vehicleId: Id[Vehicle],
    tick: Int,
    passengerSchedule: PassengerSchedule,
    currentPassengerScheduleIndex: Int,
  ) extends InterruptReply

  case class InterruptedWhileIdle(interruptId: Id[Interrupt], vehicleId: Id[Vehicle], tick: Int) extends InterruptReply

  case object Idle extends BeamAgentState

  case object Offline extends BeamAgentState

  case object IdleInterrupted extends BeamAgentState

  case class StartShiftTrigger(tick: Int) extends Trigger
  case class EndShiftTrigger(tick: Int) extends Trigger

}

class RideHailAgent(
  override val id: Id[RideHailAgent],
  rideHailManager: ActorRef,
  val scheduler: ActorRef,
  vehicle: BeamVehicle,
  initialLocation: Coord,
  val shifts: Option[List[Range]],
  val geofence: Option[Geofence],
  val eventsManager: EventsManager,
  val parkingManager: ActorRef,
  val beamServices: BeamServices,
  val transportNetwork: TransportNetwork,
  val tollCalculator: TollCalculator
) extends BeamAgent[RideHailAgentData]
    with DrivesVehicle[RideHailAgentData]
    with Stash {

  val myUnhandled: StateFunction = {
    case Event(TriggerWithId(StartShiftTrigger(tick), triggerId), _) =>
      // Wait five minutes
      stay() replying CompletionNotice(triggerId, Vector(ScheduleTrigger(StartShiftTrigger(tick + 300), self)))

    case Event(TriggerWithId(EndShiftTrigger(tick), triggerId), _) =>
      // Wait five minutes
      stay() replying CompletionNotice(triggerId, Vector(ScheduleTrigger(EndShiftTrigger(tick + 300), self)))

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
  onTransition {
    case _ -> _ =>
      unstashAll()
  }

  override def logDepth: Int = beamServices.beamConfig.beam.debug.actor.logDepth

  startWith(Uninitialized, RideHailAgentData(vehicle))

  when(Uninitialized) {
    case Event(TriggerWithId(InitializeTrigger(tick), triggerId), data) =>
      beamVehicles.put(vehicle.id, ActualVehicle(vehicle))
      vehicle.becomeDriver(self)
      vehicle.manager = Some(rideHailManager)
      eventsManager.processEvent(
        new PersonDepartureEvent(tick, Id.createPersonId(id), Id.createLinkId(""), "be_a_tnc_driver")
      )
      eventsManager.processEvent(new PersonEntersVehicleEvent(tick, Id.createPersonId(id), vehicle.id))
      val isTimeForShift = shifts.isEmpty || shifts.get
        .find(shift => shift.lowerEndpoint <= tick && shift.upperEndpoint >= tick)
        .isDefined
      if (isTimeForShift) {
        rideHailManager ! NotifyVehicleIdle(
          vehicle.id,
          vehicle.spaceTime,
          PassengerSchedule(),
          vehicle.getState,
          Some(triggerId)
        )
        holdTickAndTriggerId(tick, triggerId)
        goto(Idle) using data
          .copy(currentVehicle = Vector(vehicle.id), remainingShifts = shifts.getOrElse(List()))
      } else {
        val nextShiftStartTime = shifts.get.head.lowerEndpoint
        goto(Offline) replying CompletionNotice(
          triggerId,
          Vector(ScheduleTrigger(StartShiftTrigger(nextShiftStartTime), self))
        ) using data
          .copy(currentVehicle = Vector(vehicle.id), remainingShifts = shifts.get)
      }
  }
  when(Offline) {
    case Event(TriggerWithId(StartShiftTrigger(tick), triggerId), _) =>
      log.debug("state(RideHailingAgent.Offline): starting shift {}", id)
      rideHailManager ! NotifyVehicleIdle(
        vehicle.id,
        vehicle.spaceTime.copy(time = tick),
        PassengerSchedule(),
        vehicle.getState,
        Some(triggerId)
      )
      holdTickAndTriggerId(tick, triggerId)
      goto(Idle)
  }

  when(Idle) {
    case Event(
        TriggerWithId(EndShiftTrigger(tick), triggerId),
        data @ RideHailAgentData(_, _, _, _, _)
        ) =>
      val newShiftToSchedule = if (data.remainingShifts.size < 1) {
        Vector()
      } else {
        Vector(ScheduleTrigger(StartShiftTrigger(data.remainingShifts.head.lowerEndpoint), self))
      }
      rideHailManager ! NotifyVehicleOutOfService(vehicle.id)
      goto(Offline) replying CompletionNotice(triggerId, newShiftToSchedule)
    case ev @ Event(Interrupt(interruptId: Id[Interrupt], tick), _) =>
      log.debug("state(RideHailingAgent.Idle): {}", ev)
      goto(IdleInterrupted) replying InterruptedWhileIdle(interruptId, vehicle.id, tick)
    case ev @ Event(
          NotifyVehicleResourceIdleReply(
            triggerId: Option[Long],
            newTriggers: Seq[ScheduleTrigger]
          ),
          data
        ) =>
      log.debug("state(RideHailingAgent.Idle.NotifyVehicleResourceIdleReply): {}", ev)
      data.remainingShifts.isEmpty match {
        case true =>
          handleNotifyVehicleResourceIdleReply(triggerId, newTriggers)
          stay
        case false =>
          handleNotifyVehicleResourceIdleReply(
            triggerId,
            newTriggers :+ ScheduleTrigger(EndShiftTrigger(data.remainingShifts.head.upperEndpoint), self)
          )
          stay using data.copy(remainingShifts = data.remainingShifts.tail)
      }
    case ev @ Event(
          TriggerWithId(EndRefuelTrigger(tick, sessionStart, energyInJoules), triggerId),
          data
        ) =>
      log.debug("state(RideHailingAgent.Idle.EndRefuelTrigger): {}", ev)
      holdTickAndTriggerId(tick, triggerId)
      log.debug("Ending refuel session for {}", vehicle.id)
      vehicle.addFuel(energyInJoules)
      eventsManager.processEvent(
        new RefuelEvent(
          tick,
          vehicle.stall.get.copy(locationUTM = beamServices.geo.utm2Wgs(vehicle.stall.get.locationUTM)),
          energyInJoules,
          tick - sessionStart,
          vehicle.id
        )
      )
      parkingManager ! ReleaseParkingStall(vehicle.stall.get.id)
      vehicle.unsetParkingStall()
      vehicle.manager.foreach(
        _ ! NotifyVehicleIdle(
          vehicle.id,
          SpaceTime(vehicle.stall.get.locationUTM, tick),
          data.passengerSchedule,
          vehicle.getState,
          _currentTriggerId
        )
      )
      stay()
    case ev @ Event(TriggerWithId(StartRefuelTrigger(tick), triggerId), data) =>
      log.debug("state(RideHailingAgent.Idle.StartRefuelTrigger): {}", ev)
      val (sessionDuration, energyDelivered) =
        vehicle.refuelingSessionDurationAndEnergyInJoules()

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
  }

  when(IdleInterrupted) {
    case ev @ Event(ModifyPassengerSchedule(updatedPassengerSchedule, tick, requestId), data) =>
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
        vehicle.id,
        tick
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
          data @ RideHailAgentData(_, _, _, _, _)
        ) =>
      log.debug("state(RideHailingAgent.IdleInterrupted.NotifyVehicleResourceIdleReply): {}", ev)
      data.remainingShifts.isEmpty match {
        case true =>
          handleNotifyVehicleResourceIdleReply(triggerId, newTriggers)
          stay
        case false =>
          handleNotifyVehicleResourceIdleReply(
            triggerId,
            newTriggers :+ ScheduleTrigger(EndShiftTrigger(data.remainingShifts.head.upperEndpoint), self)
          )
          stay using data.copy(remainingShifts = data.remainingShifts.tail)
      }
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
    case ev @ Event(ModifyPassengerSchedule(_, _, _), _) =>
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
  ): Unit = {
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
  }

  whenUnhandled(drivingBehavior.orElse(myUnhandled))

  onTransition {
    case _ -> Idle =>
      unstashAll()

      nextNotifyVehicleResourceIdle match {

        case Some(nextIdle) =>
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
              vehicle.id
            )
            //assert(false)
          }

          vehicle.manager.get ! nextIdle

        case None =>
      }

      nextNotifyVehicleResourceIdle = None

    case _ -> _ =>
      unstashAll()

  }

}
