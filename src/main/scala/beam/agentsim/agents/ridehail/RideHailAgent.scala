package beam.agentsim.agents.ridehail

import akka.actor.FSM.Failure
import akka.actor.{ActorRef, FSM, Props, Stash, Status}
import beam.agentsim.Resource.{NotifyVehicleIdle, NotifyVehicleOutOfService, ReleaseParkingStall}
import beam.agentsim.agents.BeamAgent._
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.agents.choice.logit.{MultinomialLogit, UtilityFunctionOperation}
import beam.agentsim.agents.modalbehaviors.DrivesVehicle
import beam.agentsim.agents.modalbehaviors.DrivesVehicle._
import beam.agentsim.agents.ridehail.RideHailAgent._
import beam.agentsim.agents.ridehail.RideHailVehicleManager.RideHailAgentLocation
import beam.agentsim.agents.vehicles.{BeamVehicle, PassengerSchedule}
import beam.agentsim.agents.{BeamAgent, InitializeTrigger}
import beam.agentsim.events.{RefuelSessionEvent, SpaceTime}
import beam.agentsim.infrastructure.parking.ParkingZoneSearch
import beam.agentsim.infrastructure.{ParkingInquiry, ParkingInquiryResponse}
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, IllegalTriggerGoToError, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.router.BeamRouter.Location
import beam.router.model.{EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.router.osm.TollCalculator
import beam.sim.common.Range
import beam.sim.{BeamScenario, BeamServices, Geofence}
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.api.core.v01.events.{PersonDepartureEvent, PersonEntersVehicleEvent}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.vehicles.Vehicle

object RideHailAgent {
  val idPrefix: String = "rideHailAgent"

  def props(
    services: BeamServices,
    beamScenario: BeamScenario,
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
        beamScenario,
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
    remainingShifts: List[Range] = List(),
    geofence: Option[Geofence] = None
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

  case class InterruptedWhileOffline(interruptId: Id[Interrupt], vehicleId: Id[Vehicle], tick: Int)
      extends InterruptReply

  case object Idle extends BeamAgentState

  case object Offline extends BeamAgentState

  case object PendingOfflineForCharging extends BeamAgentState

  case object OfflineForCharging extends BeamAgentState

  case object IdleInterrupted extends BeamAgentState

  case class StartShiftTrigger(tick: Int) extends Trigger

  case class EndShiftTrigger(tick: Int) extends Trigger

  case class StartParkingTrigger(tick: Int) extends Trigger

  case class EndParkingTrigger(tick: Int) extends Trigger

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
  val beamScenario: BeamScenario,
  val transportNetwork: TransportNetwork,
  val tollCalculator: TollCalculator
) extends BeamAgent[RideHailAgentData]
    with DrivesVehicle[RideHailAgentData]
    with Stash {

  val networkHelper = beamServices.networkHelper
  val geo = beamServices.geo

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

    case Event(Status.Failure(reason), _) =>
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
        .find(shift => shift.lowerBound <= tick && shift.upperBound >= tick)
        .isDefined
      if (isTimeForShift) {
        rideHailManager ! NotifyVehicleIdle(
          vehicle.id,
          vehicle.spaceTime,
          PassengerSchedule(),
          vehicle.getState,
          geofence,
          Some(triggerId)
        )
        holdTickAndTriggerId(tick, triggerId)
        goto(Idle) using data
          .copy(currentVehicle = Vector(vehicle.id), remainingShifts = shifts.getOrElse(List()))
      } else {
        val nextShiftStartTime = shifts.get.head.lowerBound
        goto(Offline) replying CompletionNotice(
          triggerId,
          Vector(ScheduleTrigger(StartShiftTrigger(nextShiftStartTime), self))
        ) using data
          .copy(currentVehicle = Vector(vehicle.id), remainingShifts = shifts.get)
      }
  }

  when(OfflineForCharging) {
    case ev @ Event(TriggerWithId(StartRefuelSessionTrigger(tick), triggerId), _) =>
      log.debug("state(RideHailAgent.OfflineForCharging.StartRefuelSessionTrigger): {}", ev)
      holdTickAndTriggerId(tick, triggerId)
      requestParkingStall()
      stay

    case ev @ Event(ParkingInquiryResponse(stall, _), _) =>
      log.debug("state(RideHailAgent.OfflineForCharging.ParkingInquiryResponse): {}", ev)

      vehicle.useParkingStall(stall)
      val (tick, triggerId) = releaseTickAndTriggerId()
      log.debug("Refuel started at {}, triggerId: {}", tick, triggerId)

      if (vehicle.isBEV || vehicle.isPHEV) {
        handleStartCharging(tick, vehicle) {
          Some(
            (endRefuelData: EndRefuelData) =>
              CompletionNotice(
                triggerId,
                Vector(
                  ScheduleTrigger(
                    EndRefuelSessionTrigger(
                      endRefuelData.chargingEndTick,
                      tick,
                      endRefuelData.energyDelivered,
                      Some(vehicle)
                    ),
                    self
                  )
                )
            )
          )
        }
        stay
      } else handleStartRefuel(tick, triggerId)

    case ev @ Event(Interrupt(interruptId: Id[Interrupt], tick), _) =>
      log.debug("state(RideHailAgent.OfflineForCharging.Interrupt): {}", ev)
      stay replying InterruptedWhileOffline(interruptId, vehicle.id, tick)
    case ev @ Event(
          TriggerWithId(EndRefuelSessionTrigger(tick, sessionStart, energyInJoules, _), triggerId),
          data
        ) =>
      log.debug("state(RideHailAgent.OfflineForCharging.TriggerWithId(EndRefuelSessionTrigger)): {}", ev)
      holdTickAndTriggerId(tick, triggerId)
      nextNotifyVehicleResourceIdle = nextNotifyVehicleResourceIdle.map(_.copy(triggerId = _currentTriggerId))
      val currentLocation = handleEndRefuel(energyInJoules, tick, sessionStart.toInt)
      vehicle.spaceTime = SpaceTime(currentLocation, tick)
      goto(Idle)
  }

  when(Offline) {
    case Event(TriggerWithId(StartShiftTrigger(tick), triggerId), _) =>
      log.debug("state(RideHailingAgent.Offline): starting shift {}", id)
      holdTickAndTriggerId(tick, triggerId)
      rideHailManager ! NotifyVehicleIdle(
        vehicle.id,
        vehicle.spaceTime.copy(time = tick),
        PassengerSchedule(),
        vehicle.getState,
        geofence,
        Some(triggerId)
      )
      goto(Idle)
    case ev @ Event(Interrupt(interruptId: Id[Interrupt], tick), _) =>
      log.debug("state(RideHailingAgent.Offline): {}", ev)
      stay replying InterruptedWhileOffline(interruptId, vehicle.id, tick)
    case ev @ Event(
          reply @ NotifyVehicleResourceIdleReply(_, _),
          data
        ) =>
      log.debug("state(RideHailingAgent.Idle.NotifyVehicleResourceIdleReply): {}", ev)
      handleNotifyVehicleResourceIdleReply(reply, data)
    case ev @ Event(TriggerWithId(StartRefuelSessionTrigger(tick), triggerId), _) =>
      log.debug("state(RideHailingAgent.Offline.StartRefuelSessionTrigger): triggerId: {}, ev: {}", triggerId, ev)
      handleStartRefuel(tick, triggerId)
    case ev @ Event(
          TriggerWithId(EndRefuelSessionTrigger(tick, sessionStart, energyInJoules, _), triggerId),
          data
        ) =>
      log.debug("state(RideHailingAgent.Offline.EndRefuelSessionTrigger): triggerId: {}, ev: {}", triggerId, ev)
      val currentLocation = handleEndRefuel(energyInJoules, tick, sessionStart.toInt)
      vehicle.spaceTime = SpaceTime(currentLocation, tick)
      stay() replying CompletionNotice(triggerId)
  }

  when(Idle) {
    case Event(
        TriggerWithId(EndShiftTrigger(tick), triggerId),
        data @ RideHailAgentData(_, _, _, _, _, _)
        ) =>
      val newShiftToSchedule = if (data.remainingShifts.size < 1) {
        Vector()
      } else {
        Vector(ScheduleTrigger(StartShiftTrigger(data.remainingShifts.head.lowerBound), self))
      }
      rideHailManager ! NotifyVehicleOutOfService(vehicle.id)
      goto(Offline) replying CompletionNotice(triggerId, newShiftToSchedule)
    case ev @ Event(Interrupt(interruptId: Id[Interrupt], tick), _) =>
      log.debug("state(RideHailingAgent.Idle): {}", ev)
      goto(IdleInterrupted) replying InterruptedWhileIdle(interruptId, vehicle.id, tick)
    case ev @ Event(
          reply @ NotifyVehicleResourceIdleReply(_, _),
          data
        ) =>
      log.debug("state(RideHailingAgent.Idle.NotifyVehicleResourceIdleReply): {}", ev)
      handleNotifyVehicleResourceIdleReply(reply, data)
    case ev @ Event(
          TriggerWithId(EndRefuelSessionTrigger(tick, sessionStart, energyInJoules, _), triggerId),
          data
        ) =>
      log.debug("state(RideHailingAgent.Idle.EndRefuelSessionTrigger): {}", ev)
      holdTickAndTriggerId(tick, triggerId)
      val currentLocation = handleEndRefuel(energyInJoules, tick, sessionStart.toInt)
      vehicle.manager.foreach(
        _ ! NotifyVehicleIdle(
          vehicle.id,
          SpaceTime(currentLocation, tick),
          data.passengerSchedule,
          vehicle.getState,
          geofence,
          Some(triggerId)
        )
      )
      stay()
    case ev @ Event(TriggerWithId(StartRefuelSessionTrigger(tick), triggerId), _) =>
      log.debug("state(RideHailingAgent.Idle.StartRefuelSessionTrigger): {}", ev)
      handleStartRefuel(tick, triggerId)
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
        .copy(geofence = geofence)
        .withPassengerSchedule(updatedPassengerSchedule)
        .asInstanceOf[RideHailAgentData] replying ModifyPassengerScheduleAck(
        requestId,
        triggerToSchedule,
        vehicle.id,
        tick,
      )
    case ev @ Event(Resume(), _) =>
      log.debug("state(RideHailingAgent.IdleInterrupted): {}", ev)
      goto(Idle)
    case ev @ Event(Interrupt(interruptId: Id[Interrupt], tick), _) =>
      log.debug("state(RideHailingAgent.IdleInterrupted): {}", ev)
      stay() replying InterruptedWhileIdle(interruptId, vehicle.id, tick)
    case ev @ Event(
          reply @ NotifyVehicleResourceIdleReply(_, _),
          data
        ) =>
      log.debug("state(RideHailingAgent.Idle.NotifyVehicleResourceIdleReply): {}", ev)
      handleNotifyVehicleResourceIdleReply(reply, data)

  }

  when(PassengerScheduleEmpty) {
    case ev @ Event(PassengerScheduleEmptyMessage(lastTime, _, _), data) =>
      log.debug("state(RideHailingAgent.PassengerScheduleEmpty): {} Remaining Shifts: {}", ev, data.remainingShifts)
      import beam.agentsim.agents.vehicles.BeamVehicle.BeamVehicleState
      def metersToMiles(meters: Double) = meters / 1600

      def remainingRangeInMiles(vehicleState: BeamVehicleState) =
        metersToMiles(vehicleState.remainingPrimaryRangeInM) +
        metersToMiles(vehicleState.remainingSecondaryRangeInM.getOrElse(0.0))

      val remainingRangeInMilesVal = remainingRangeInMiles(vehicle.getState)
      if (!vehicle.isCAV && remainingRangeInMilesVal < 20.0) {
        /*
         if below a threshold (like 20 miles of remaining range) then we definitely go to charge.
         If range is above that, we do a random draw with a probability that increases the closer we get to 20 miles.
         So 21 miles my by 90%, 30 miles might be 75%, 40 miles 50%, etc. We can keep the relationship simple.
         Maybe we give a threshold and then the slope of a linear relationship between miles and prob.
         E.g. P(charge) = 1 - (rangeLeft - 20)*slopeParam….
         where any range that yields a negative probability would just be truncated to 0
         */
        log.debug("Empty human ridehail vehicle requesting parking stall: event = " + ev)
        rideHailManager ! NotifyVehicleOutOfService(vehicle.id)

        //Should I use the tick or the last time?
        val (_, triggerId) = releaseTickAndTriggerId()
        val startFuelTrigger = ScheduleTrigger(
          StartRefuelSessionTrigger(lastTime.time),
          self
        )
        scheduler ! CompletionNotice(triggerId, Vector(startFuelTrigger))

        goto(OfflineForCharging) using data
          .withPassengerSchedule(PassengerSchedule())
          .withCurrentLegPassengerScheduleIndex(0)
          .asInstanceOf[RideHailAgentData]
      } else if (!vehicle.isCAV && remainingRangeInMilesVal > 20.0) {
        val percentageChanceToRefuel = Math.max(100 - (remainingRangeInMilesVal.toInt - 20), 0)
        val randomChance = scala.util.Random.nextInt(100)
        if (randomChance < percentageChanceToRefuel) {
          log.debug("Empty human ridehail vehicle requesting parking stall since percentage hit: event = " + ev)
          rideHailManager ! NotifyVehicleOutOfService(vehicle.id)

          //Should I use the tick or the last time?
          val (_, triggerId) = releaseTickAndTriggerId()
          val startFuelTrigger = ScheduleTrigger(
            StartRefuelSessionTrigger(lastTime.time),
            self
          )
          scheduler ! CompletionNotice(triggerId, Vector(startFuelTrigger))

          goto(OfflineForCharging) using data
            .withPassengerSchedule(PassengerSchedule())
            .withCurrentLegPassengerScheduleIndex(0)
            .asInstanceOf[RideHailAgentData]
        } else {
          log.debug("Empty human ridehail vehicle NOT requesting parking stall: event = " + ev)
          goto(Idle) using data
            .withPassengerSchedule(PassengerSchedule())
            .withCurrentLegPassengerScheduleIndex(0)
            .asInstanceOf[RideHailAgentData]
        }
      } else {
        log.debug("Ridehail vehicle default NOT Empty human: event = " + ev)
        goto(Idle) using data
          .withPassengerSchedule(PassengerSchedule())
          .withCurrentLegPassengerScheduleIndex(0)
          .asInstanceOf[RideHailAgentData]
      }
    case ev @ Event(Interrupt(_, _), _) =>
      log.debug("state(RideHailingAgent.PassengerScheduleEmpty): {}", ev)
      stash()
      stay()
  }

  when(PassengerScheduleEmptyInterrupted) {
    case ev @ Event(PassengerScheduleEmptyMessage(_, _, _), data) =>
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

  def requestParkingStall(): Unit = {
    val rideHailAgentLocation =
      RideHailAgentLocation(vehicle.driver.get, vehicle.id, vehicle.beamVehicleType.id, vehicle.spaceTime, geofence)
    val destinationUtm = rideHailAgentLocation.currentLocationUTM.loc
    val beta1 = 1
    val beta2 = 1
    val beta3 = 0.001
    val commonUtilityParams: Map[String, UtilityFunctionOperation] = Map(
      "energyPriceFactor" -> UtilityFunctionOperation("multiplier", -beta1),
      "distanceFactor"    -> UtilityFunctionOperation("multiplier", -beta2),
      "installedCapacity" -> UtilityFunctionOperation("multiplier", -beta3)
    )
    val mnl = new MultinomialLogit[ParkingZoneSearch.ParkingAlternative, String](Map.empty, commonUtilityParams)
    val inquiry = ParkingInquiry(destinationUtm, "charge", 0.0, mnl, 0.0, Option(vehicle))
    parkingManager ! inquiry
  }

  def handleStartRefuel(tick: Int, triggerId: Long): State = {
    val (sessionDuration, energyDelivered) =
      vehicle.refuelingSessionDurationAndEnergyInJoules()

    log.debug(
      "scheduling EndRefuelSessionTrigger at {} with {} J to be delivered, triggerId: {}",
      tick + sessionDuration.toInt,
      energyDelivered,
      triggerId
    )
    scheduler ! CompletionNotice(
      triggerId,
      Vector(
        ScheduleTrigger(EndRefuelSessionTrigger(tick + sessionDuration.toInt, tick, energyDelivered), self)
      )
    )
    stay()
  }

  def handleEndRefuel(energyInJoules: Double, tick: Int, sessionStart: Int): Location = {
    log.debug("Ending refuel session for {}", vehicle.id)
    vehicle.addFuel(energyInJoules)
    eventsManager.processEvent(
      new RefuelSessionEvent(
        tick,
        vehicle.stall.get.copy(locationUTM = beamServices.geo.utm2Wgs(vehicle.stall.get.locationUTM)),
        energyInJoules,
        tick - sessionStart,
        vehicle.id
      )
    )
    parkingManager ! ReleaseParkingStall(vehicle.stall.get.parkingZoneId)
    val currentLocation = vehicle.stall.get.locationUTM
    vehicle.unsetParkingStall()
    currentLocation
  }

  def handleNotifyVehicleResourceIdleReply(
    ev: NotifyVehicleResourceIdleReply,
    data: RideHailAgentData
  ): FSM.State[BeamAgentState, RideHailAgentData] = {
    log.debug("state(RideHailingAgent.IdleInterrupted.NotifyVehicleResourceIdleReply): {}", ev)
    data.remainingShifts.isEmpty match {
      case true =>
        completeHandleNotifyVehicleResourceIdleReply(ev.triggerId, ev.newTriggers)
        stay
      case false =>
        completeHandleNotifyVehicleResourceIdleReply(
          ev.triggerId,
          ev.newTriggers :+ ScheduleTrigger(EndShiftTrigger(data.remainingShifts.head.upperBound), self)
        )
        stay using data.copy(remainingShifts = data.remainingShifts.tail)
    }
  }

  def completeHandleNotifyVehicleResourceIdleReply(
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
              "RHA {}: state(transitioning to Idle NotifyVehicleResourceIdleReply) - ev: {}, triggerId: {}",
              id,
              nextIdle,
              _
            )
          )
          if (_currentTriggerId != nextIdle.triggerId) {
            log.error(
              "RHA {}: _currentTriggerId({}) and nextNotifyVehicleResourceIdle.triggerId({}) don't match - vehicleId({})",
              id,
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
