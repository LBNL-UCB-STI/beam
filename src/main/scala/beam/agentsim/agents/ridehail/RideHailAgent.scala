package beam.agentsim.agents.ridehail

import akka.actor.FSM.Failure
import akka.actor.{ActorRef, FSM, Props, Stash, Status}
import beam.agentsim.Resource.{
  NotifyVehicleDoneRefuelingAndOutOfService,
  NotifyVehicleIdle,
  NotifyVehicleOutOfService,
  ReleaseParkingStall
}
import beam.agentsim.agents.BeamAgent._
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.agents.modalbehaviors.DrivesVehicle
import beam.agentsim.agents.modalbehaviors.DrivesVehicle._
import beam.agentsim.agents.ridehail.RideHailAgent._
import beam.agentsim.agents.ridehail.RideHailManager.MarkVehicleBatteryDepleted
import beam.agentsim.agents.ridehail.RideHailManagerHelper.RideHailAgentLocation
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.agents.vehicles.{BeamVehicle, PassengerSchedule}
import beam.agentsim.agents.{BeamAgent, InitializeTrigger}
import beam.agentsim.events.RefuelSessionEvent.{OffShift, OnShift}
import beam.agentsim.events.ShiftEvent.{EndShift, StartShift}
import beam.agentsim.events.{ShiftEvent, _}
import beam.agentsim.infrastructure.{ParkingInquiry, ParkingInquiryResponse, ParkingStall}
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, IllegalTriggerGoToError, ScheduleTrigger}
import beam.agentsim.scheduler.{HasTriggerId, Trigger}
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.router.BeamRouter.{RoutingRequest, RoutingResponse}
import beam.router.Modes.BeamMode.CAR
import beam.router.model.{BeamLeg, EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.router.osm.TollCalculator
import beam.sim.common.GeoUtils
import beam.sim.{BeamScenario, BeamServices, Geofence}
import beam.utils.NetworkHelper
import beam.utils.logging.LogActorState
import beam.utils.reflection.ReflectionUtils
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.api.core.v01.events.{PersonDepartureEvent, PersonEntersVehicleEvent}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.utils.misc.Time
import org.matsim.vehicles.Vehicle

import scala.collection.mutable

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
    chargingNetworkManager: ActorRef,
    rideHailAgentId: Id[RideHailAgent],
    rideHailManager: ActorRef,
    vehicle: BeamVehicle,
    location: Coord,
    shifts: Option[List[Shift]],
    geofence: Option[Geofence]
  ): Props =
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
        chargingNetworkManager,
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
    remainingShifts: List[Shift] = List(),
    geofence: Option[Geofence] = None
  ) extends DrivingData {
    override def withPassengerSchedule(newPassengerSchedule: PassengerSchedule): DrivingData =
      copy(passengerSchedule = newPassengerSchedule)

    override def withCurrentLegPassengerScheduleIndex(
      newLegPassengerScheduleIndex: Int
    ): DrivingData = copy(currentLegPassengerScheduleIndex = newLegPassengerScheduleIndex)

    override def hasParkingBehaviors: Boolean = false
    override def legStartsAt: Option[Int] = None
  }

  // triggerId is included to facilitate debugging
  case class NotifyVehicleResourceIdleReply(
    triggerId: Long,
    newTriggers: Seq[ScheduleTrigger],
    vehicleInQueueParkingZoneId: Option[Int] = None
  ) extends HasTriggerId
  case class NotifyVehicleDoneRefuelingAndOutOfServiceReply(
    triggerId: Long,
    newTriggers: Seq[ScheduleTrigger],
    vehicleInQueueParkingZoneId: Option[Int] = None
  ) extends HasTriggerId

  case class ModifyPassengerSchedule(
    updatedPassengerSchedule: PassengerSchedule,
    tick: Int,
    triggerId: Long,
    reservationRequestId: Option[Int] = None
  ) extends HasTriggerId

  case class ModifyPassengerScheduleAck(
    reservationRequestId: Option[Int] = None,
    triggersToSchedule: Vector[ScheduleTrigger],
    vehicleId: Id[Vehicle],
    tick: Int,
    triggerId: Long
  ) extends HasTriggerId

  case class ModifyPassengerScheduleAcks(acks: List[ModifyPassengerScheduleAck], triggerId: Long) extends HasTriggerId

  case class Interrupt(interruptId: Int, tick: Int, triggerId: Long) extends HasTriggerId

  case class Resume(triggerId: Long) extends HasTriggerId

  sealed trait InterruptReply {
    val interruptId: Int
    val vehicleId: Id[BeamVehicle]
    val tick: Int
  }

  case class InterruptedWhileDriving(
    interruptId: Int,
    vehicleId: Id[BeamVehicle],
    tick: Int,
    passengerSchedule: PassengerSchedule,
    currentPassengerScheduleIndex: Int,
    triggerId: Long,
  ) extends InterruptReply
      with HasTriggerId

  case class InterruptedWhileIdle(interruptId: Int, vehicleId: Id[BeamVehicle], tick: Int, triggerId: Long)
      extends InterruptReply
      with HasTriggerId

  case class InterruptedWhileOffline(interruptId: Int, vehicleId: Id[BeamVehicle], tick: Int, triggerId: Long)
      extends InterruptReply
      with HasTriggerId
  case class InterruptedWhileWaitingToDrive(interruptId: Int, vehicleId: Id[BeamVehicle], tick: Int, triggerId: Long)
      extends InterruptReply
      with HasTriggerId

  case object Idle extends BeamAgentState

  case object InQueue extends BeamAgentState
  case object InQueueInterrupted extends BeamAgentState

  case object Refueling extends BeamAgentState
  case object RefuelingInterrupted extends BeamAgentState

  case object Offline extends BeamAgentState
  case object OfflineInterrupted extends BeamAgentState

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
  val shifts: Option[List[Shift]],
  val geofence: Option[Geofence],
  val eventsManager: EventsManager,
  val parkingManager: ActorRef,
  val chargingNetworkManager: ActorRef,
  val beamServices: BeamServices,
  val beamScenario: BeamScenario,
  val transportNetwork: TransportNetwork,
  val tollCalculator: TollCalculator
) extends BeamAgent[RideHailAgentData]
    with DrivesVehicle[RideHailAgentData]
    with Stash {
  override val eventBuilderActor: ActorRef = beamServices.eventBuilderActor

  val networkHelper: NetworkHelper = beamServices.networkHelper
  val geo: GeoUtils = beamServices.geo

  val lastTickOfSimulation = Time
    .parseTime(beamScenario.beamConfig.beam.agentsim.endTime)
    .toInt - beamServices.beamConfig.beam.agentsim.schedulerParallelismWindow
  var isOnWayToParkAtStall: Option[ParkingStall] = None
  var isStartingNewShift: Boolean = false
  var isCurrentlyOnShift: Boolean = false
  var isInQueueParkingZoneId: Option[Int] = None
  val beamLegsToIgnoreDueToNewPassengerSchedule = mutable.HashSet[BeamLeg]()
  var needsToEndShift: Boolean = false
  var waitingForDoneRefuelingAndOutOfServiceReply: Boolean = false

  // Useful for debugging
  val debugEnabled: Boolean = beamScenario.beamConfig.beam.debug.debugEnabled
  val outgoingMessages: mutable.ListBuffer[Any] = new mutable.ListBuffer[Any]()
  var lastLocationOfRefuel: Option[Coord] = None // for detecting teleportations

  val startShiftTriggerTimeout = Math.max(
    beamScenario.beamConfig.beam.agentsim.schedulerParallelismWindow,
    1
  )

  val myUnhandled: StateFunction = {
    case ev @ Event(TriggerWithId(StartShiftTrigger(tick), triggerId), _) =>
      // Wait five minutes
      val tickToSchedule = Math.min(tick + startShiftTriggerTimeout, lastTickOfSimulation)
      val completeNotice = if (tickToSchedule > tick) {
        CompletionNotice(
          triggerId,
          Vector(ScheduleTrigger(StartShiftTrigger(Math.max(tick, tickToSchedule)), self))
        )
      } else {
        CompletionNotice(triggerId, Vector())
      }
      if (debugEnabled) {
        outgoingMessages += ev
        outgoingMessages += completeNotice
      }
      stay() replying completeNotice

    case Event(TriggerWithId(EndShiftTrigger(_), triggerId), _) =>
      // Mark that end shift is needed and complete
      needsToEndShift = true
      stay() replying CompletionNotice(triggerId, Vector())

    case ev @ Event(TriggerWithId(EndLegTrigger(tick), triggerId), data) =>
      log.debug(
        "myUnhandled state({}): ignoring EndLegTrigger probably because of a modifyPassSchedule: {}",
        stateName,
        ev
      )
      sender() ! CompletionNotice(triggerId)
      if (beamLegsToIgnoreDueToNewPassengerSchedule.find(_.endTime == tick).isEmpty) {
        log.debug(s"Received unrecognized EndLegTrigger $ev while in state $stateName")
      }
      stay

    case ev @ Event(TriggerWithId(StartLegTrigger(_, leg), triggerId), data) =>
      log.debug(
        "myUnhandled state({}): stashing StartLegTrigger probably because interrupt was received while in WaitingToDrive before getting this trigger: {}",
        stateName,
        ev
      )
      // if we have stored this leg, we know it should be ignore due to a change in pass schedule
      if (beamLegsToIgnoreDueToNewPassengerSchedule.contains(leg)) {
        sender() ! CompletionNotice(triggerId)
      } else {
        stash
      }
      stay

    case ev @ Event(ModifyPassengerSchedule(_, tick, triggerId, requestId), _) =>
      log.warning(
        "myUnhandled state({}): ignoring ModifyPassengerSchedule message and reply with ack: {}",
        stateName,
        ev
      )
      stay replying ModifyPassengerScheduleAck(
        requestId,
        Vector(),
        vehicle.id,
        tick,
        triggerId
      )

    case ev @ Event(IllegalTriggerGoToError(reason), _) =>
      log.debug("myUnhandled state({}): {}", stateName, ev)
      stop(Failure(reason))

    case Event(Status.Failure(reason), _) =>
      stop(Failure(reason))

    case ev @ Event(Finish, _) =>
      log.debug("myUnhandled state({}): {}", stateName, ev)
      if (isCurrentlyOnShift) {
        val actualLastTick = Time.parseTime(beamScenario.beamConfig.beam.agentsim.endTime).toInt - 1
        eventsManager.processEvent(new ShiftEvent(actualLastTick, EndShift, id.toString, vehicle))
      }
      stop

    // This can happen if the NotifyVehicleIdle is sent to RHM after RHM starts a buffered allocation process
    // and meanwhile dispatches this RHA who has now moved on to other things. This is how we complete the trigger
    // that made this RHA available in the first place
    case ev @ Event(NotifyVehicleResourceIdleReply(_, _, _), _) =>
      log.debug("myUnhandled state({}): releaseTickAndTrigger if needed {}", stateName, ev)
      _currentTriggerId match {
        case Some(_) =>
          val (_, triggerId) = releaseTickAndTriggerId()
          scheduler ! CompletionNotice(triggerId, Vector())
        case None =>
      }
      stay

    case Event(LogActorState, _) =>
      ReflectionUtils.logFields(log, this, 0)
      log.info(getLog.map(entry => (entry.stateName, entry.event, entry.stateData)).mkString("\n\t"))
      stay

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
      vehicle.setManager(Some(rideHailManager))
      eventsManager.processEvent(
        new PersonDepartureEvent(tick, Id.createPersonId(id), Id.createLinkId(""), "be_a_tnc_driver")
      )
      eventsManager.processEvent(new PersonEntersVehicleEvent(tick, Id.createPersonId(id), vehicle.id))
      val isTimeForShift = shifts.isEmpty || shifts.get
        .find(shift => shift.range.lowerBound <= tick && shift.range.upperBound >= tick)
        .isDefined
      if (isTimeForShift) {
        eventsManager.processEvent(new ShiftEvent(tick, StartShift, id.toString, vehicle))
        rideHailManager ! NotifyVehicleIdle(
          vehicle.id,
          vehicle.spaceTime,
          PassengerSchedule(),
          vehicle.getState,
          geofence,
          triggerId
        )
        holdTickAndTriggerId(tick, triggerId)
        isCurrentlyOnShift = true
        isStartingNewShift = true
        goto(Idle) using data
          .copy(currentVehicle = Vector(vehicle.id), remainingShifts = shifts.getOrElse(List()))
      } else {
        val nextShiftStartTime = shifts.get.head.range.lowerBound
        goto(Offline) replying CompletionNotice(
          triggerId,
          Vector(ScheduleTrigger(StartShiftTrigger(nextShiftStartTime), self))
        ) using data
          .copy(currentVehicle = Vector(vehicle.id), remainingShifts = shifts.get)
      }
  }

  when(Offline) {
    case ev @ Event(ParkingInquiryResponse(stall, _, triggerId), _) =>
      log.debug("state(RideHailAgent.Offline.ParkingInquiryResponse): {}", ev)
      val currentLocationUTM = beamServices.geo.wgs2Utm(currentBeamVehicle.spaceTime.loc)
      vehicle.useParkingStall(stall)
      val carStreetVeh =
        StreetVehicle(
          currentBeamVehicle.id,
          currentBeamVehicle.beamVehicleType.id,
          SpaceTime(currentLocationUTM, _currentTick.get),
          CAR,
          asDriver = true,
          needsToCalculateCost = true
        )
      val veh2StallRequest = RoutingRequest(
        currentLocationUTM,
        stall.locationUTM,
        _currentTick.get,
        withTransit = false,
        personId = None,
        Vector(carStreetVeh),
        None,
        triggerId = triggerId
      )
      isOnWayToParkAtStall = Some(stall)
      beamServices.beamRouter ! veh2StallRequest
      stay
    case Event(RoutingResponse(itineraries, _, _, _, _), data) =>
      log.debug("Received routing response, initiating trip to parking stall")
      val theLeg = itineraries.head.beamLegs.head
      val updatedPassengerSchedule = PassengerSchedule().addLegs(Seq(theLeg))
      val (tick, triggerId) = releaseTickAndTriggerId()
      scheduler ! CompletionNotice(
        triggerId,
        Vector(
          ScheduleTrigger(StartLegTrigger(tick, theLeg), self)
        )
      )
      goto(WaitingToDrive) using data
        .copy(geofence = geofence)
        .withPassengerSchedule(updatedPassengerSchedule)
        .asInstanceOf[RideHailAgentData]
    case ev @ Event(
          NotifyVehicleDoneRefuelingAndOutOfServiceReply(triggerId, newTriggers, vehicleInQueueParkingZoneId),
          data
        ) =>
      waitingForDoneRefuelingAndOutOfServiceReply = false
      val (tick, localTriggerId) = releaseTickAndTriggerId()
      assert(localTriggerId == triggerId)
      if (newTriggers.headOption.exists(_.trigger.tick < tick)) {
        log.error(
          s"agent({}) state(RideHailingAgent.Offline): NotifyVehicleDoneRefuelingAndOutOfServiceReply detected trigger {} with tick before the one about to be completed {}",
          id,
          newTriggers.head,
          tick
        )
      }
      val newShiftToSchedule = needsToEndShift match {
        case true =>
          eventsManager.processEvent(new ShiftEvent(tick, EndShift, id.toString, vehicle))
          isCurrentlyOnShift = false
          needsToEndShift = false
          if (data.remainingShifts.size < 1) {
            Vector()
          } else {
            val tickToSchedule = Math.min(data.remainingShifts.head.range.lowerBound, lastTickOfSimulation)
            Vector(ScheduleTrigger(StartShiftTrigger(Math.max(tickToSchedule, tick)), self))
          }
        case false =>
          Vector()
      }
      if (debugEnabled) outgoingMessages += ev
      if (debugEnabled) outgoingMessages += CompletionNotice(triggerId, newTriggers ++ newShiftToSchedule)
      scheduler ! CompletionNotice(triggerId, newTriggers ++ newShiftToSchedule)
      unstashAll() // needed in case StartShiftTrigger was stashed (see next block)
      stay()
    case ev @ Event(TriggerWithId(StartShiftTrigger(tick), triggerId), data) =>
      if (waitingForDoneRefuelingAndOutOfServiceReply) {
        stash()
        stay()
      } else {
        if (needsToEndShift) {
          eventsManager.processEvent(new ShiftEvent(tick, EndShift, id.toString, vehicle))
          needsToEndShift = false
          isCurrentlyOnShift = false
        }
        updateLatestObservedTick(tick)
        eventsManager.processEvent(new ShiftEvent(tick, StartShift, id.toString, vehicle))
        log.debug("state(RideHailingAgent.Offline): starting shift {}", id)
        holdTickAndTriggerId(tick, triggerId)
        isStartingNewShift = true
        val newLocation = data.remainingShifts.headOption match {
          case Some(Shift(_, Some(startLocation))) =>
            //TODO this is teleportation and should be fixed in favor of new protocol to make vehicles move
            SpaceTime(startLocation, time = tick)
          case _ =>
            vehicle.spaceTime.copy(time = tick)
        }
        if (debugEnabled) outgoingMessages += ev
        if (debugEnabled)
          outgoingMessages += NotifyVehicleIdle(
            vehicle.id,
            newLocation,
            PassengerSchedule(),
            vehicle.getState,
            geofence,
            triggerId
          )
        rideHailManager ! NotifyVehicleIdle(
          vehicle.id,
          newLocation,
          PassengerSchedule(),
          vehicle.getState,
          geofence,
          triggerId
        )
        goto(Idle)
      }
    case ev @ Event(Interrupt(interruptId, tick, triggerId), _) =>
      log.debug("state(RideHailingAgent.Offline): {}", ev)
      goto(OfflineInterrupted) replying InterruptedWhileOffline(interruptId, vehicle.id, latestObservedTick, triggerId)
    case ev @ Event(Resume(_), _) =>
      log.debug("state(RideHailingAgent.Offline): {}", ev)
      stay
    case ev @ Event(
          reply @ NotifyVehicleResourceIdleReply(_, _, _),
          data
        ) =>
      log.debug("state(RideHailingAgent.Idle.NotifyVehicleResourceIdleReply): {}", ev)
      handleNotifyVehicleResourceIdleReply(reply, data)
    case ev @ Event(TriggerWithId(StartRefuelSessionTrigger(tick), triggerId), data) =>
      // Due to parallelism window and dequeue process, tick could be unchronological
      val tickToUse = Math.max(tick, latestObservedTick)
      updateLatestObservedTick(tick)
      log.debug("state(RideHailAgent.Offline.StartRefuelSessionTrigger): {}", ev)
      if (vehicle.isCAV) {
        if (debugEnabled) outgoingMessages += ev
        startRefueling(tickToUse, triggerId, data)
        goto(Refueling)
      } else {
        holdTickAndTriggerId(tickToUse, triggerId)
        requestParkingStall()
        stay
      }
    case ev @ Event(TriggerWithId(StartLegTrigger(_, _), triggerId), data) =>
      log.warning(
        "state(RideHailingAgent.Offline.StartLegTrigger) this should be avoided instead of what I'm about to do which is ignore and complete this trigger: {} ",
        ev
      )
      stay replying CompletionNotice(triggerId)
  }
  when(OfflineInterrupted) {
    case Event(Resume(_), _) =>
      log.debug("state(RideHailingAgent.Offline.Resume)")
      goto(Offline)
    case Event(TriggerWithId(StartShiftTrigger(_), _), _) =>
      stash()
      stay()
    case ev @ Event(Interrupt(_, _, _), _) =>
      stash()
      stay()
    case ev @ Event(NotifyVehicleResourceIdleReply(_, _, _), _) =>
      stash()
      stay()
    case ev @ Event(NotifyVehicleDoneRefuelingAndOutOfServiceReply(_, _, _), _) =>
      stash()
      stay()
    case ev @ Event(TriggerWithId(StartRefuelSessionTrigger(tick), triggerId), _) =>
      stash()
      stay()
    case ev @ Event(TriggerWithId(EndRefuelSessionTrigger(_, _, _, _), _), _) =>
      stash()
      stay()
    case ev @ Event(ParkingInquiryResponse(_, _, _), _) =>
      stash()
      stay()
    case ev @ Event(RoutingResponse(_, _, _, _, _), _) =>
      stash()
      stay()
    case ev @ Event(ModifyPassengerSchedule(_, _, _, _), _) =>
      stash()
      goto(IdleInterrupted)
  }

  when(Idle) {
    case ev @ Event(
          TriggerWithId(EndShiftTrigger(tick), triggerId),
          data @ RideHailAgentData(_, _, _, _, _, _)
        ) =>
      updateLatestObservedTick(tick)
      eventsManager.processEvent(new ShiftEvent(tick, EndShift, id.toString, vehicle))
      isCurrentlyOnShift = false
      val newShiftToSchedule = if (data.remainingShifts.size < 1) {
        Vector()
      } else {
        //TODO if shift location specified, initiate movement here, storing shift and scheduling StartTrigger after getting PassSchedEmpty
        val tickToSchedule = Math.min(data.remainingShifts.head.range.lowerBound, lastTickOfSimulation)
        Vector(ScheduleTrigger(StartShiftTrigger(Math.max(tickToSchedule, tick)), self))
      }
      if (debugEnabled) outgoingMessages += ev
      if (debugEnabled) outgoingMessages += NotifyVehicleOutOfService(vehicle.id, triggerId)
      rideHailManager ! NotifyVehicleOutOfService(vehicle.id, triggerId)
      if (debugEnabled) outgoingMessages += CompletionNotice(triggerId, newShiftToSchedule)
      goto(Offline) replying CompletionNotice(triggerId, newShiftToSchedule)
    case ev @ Event(Interrupt(interruptId, tick, triggerId), _) =>
      log.debug("state(RideHailingAgent.Idle): {}", ev)
      goto(IdleInterrupted) replying InterruptedWhileIdle(interruptId, vehicle.id, latestObservedTick, triggerId)
    case ev @ Event(
          reply @ NotifyVehicleResourceIdleReply(_, _, _),
          data
        ) =>
      log.debug("state(RideHailingAgent.Idle.NotifyVehicleResourceIdleReply): {}", ev)
      handleNotifyVehicleResourceIdleReply(reply, data)
    case ev @ Event(TriggerWithId(StartRefuelSessionTrigger(tick), triggerId), data) =>
      // Due to parallelism window and dequeue process, tick could be unchronological
      val tickToUse = Math.max(tick, latestObservedTick)
      updateLatestObservedTick(tick)
      log.debug("state(RideHailingAgent.Idle.StartRefuelSessionTrigger): {}", ev)
      if (debugEnabled) outgoingMessages += ev
      startRefueling(tickToUse, triggerId, data)
      goto(Refueling)
  }

  when(IdleInterrupted) {
    case ev @ Event(ModifyPassengerSchedule(updatedPassengerSchedule, tick, triggerId, requestId), data) =>
      updateLatestObservedTick(tick)
      lastLocationOfRefuel match {
        case Some(loc) =>
          val dist = beamServices.geo.distUTMInMeters(
            loc,
            beamServices.geo.wgs2Utm(updatedPassengerSchedule.schedule.head._1.travelPath.startPoint.loc)
          )
          if (beamServices.geo.distUTMInMeters(
                loc,
                beamServices.geo.wgs2Utm(updatedPassengerSchedule.schedule.head._1.travelPath.startPoint.loc)
              ) > 1500.0) {
            val legStartingLoc =
              beamServices.geo.wgs2Utm(updatedPassengerSchedule.schedule.head._1.travelPath.startPoint.loc)
            log.warning(
              "potential teleportation happening, refuel coord {}, new BeamLeg.startPoint {}, dist {}",
              loc,
              legStartingLoc,
              dist
            )
            val i = 0
          }
          lastLocationOfRefuel = None
        case None =>
      }
      log.debug("state(RideHailingAgent.IdleInterrupted): {}", ev)
      // This is a message from another agent, the ride-hailing manager. It is responsible for "keeping the trigger",
      // i.e. for what time it is.
      if (data.passengerSchedule.schedule.isEmpty) {
        log.debug("updating Passenger schedule - vehicleId({}): {}", id, updatedPassengerSchedule)
        val triggerToSchedule =
          scheduleStartLegIfFeasible(updatedPassengerSchedule, updatedPassengerSchedule.schedule.firstKey)
        goto(WaitingToDriveInterrupted) using data
          .copy(geofence = geofence)
          .withPassengerSchedule(updatedPassengerSchedule)
          .asInstanceOf[RideHailAgentData] replying ModifyPassengerScheduleAck(
          requestId,
          triggerToSchedule,
          vehicle.id,
          tick,
          triggerId
        )
      } else {
        val currentLeg = data.passengerSchedule.schedule.view.drop(data.currentLegPassengerScheduleIndex).head._1
        beamLegsToIgnoreDueToNewPassengerSchedule.add(currentLeg)
        val updatedStopTime = math.max(currentLeg.startTime, tick)
        val resolvedPassengerSchedule: PassengerSchedule = DrivesVehicle.resolvePassengerScheduleConflicts(
          updatedStopTime,
          data.passengerSchedule,
          updatedPassengerSchedule,
          beamServices.networkHelper,
          beamServices.geo
        )
        log.debug(
          s"merged existing passenger schedule with updated - vehicleId({}) @ $tick, existing: {}, updated: {}, resolved: {}",
          id,
          data.passengerSchedule,
          updatedPassengerSchedule,
          resolvedPassengerSchedule
        )
        val newLegIndex = resolvedPassengerSchedule.schedule.keys.zipWithIndex
          .find(_._1.startTime <= updatedStopTime)
          .map(_._2)
          .getOrElse(0)
        val newNextLeg = resolvedPassengerSchedule.schedule.keys.toIndexedSeq(newLegIndex)

        val triggerToSchedule = scheduleStartLegIfFeasible(resolvedPassengerSchedule, newNextLeg)
        goto(WaitingToDriveInterrupted) using data
          .copy(geofence = geofence)
          .withPassengerSchedule(resolvedPassengerSchedule)
          .withCurrentLegPassengerScheduleIndex(newLegIndex)
          .asInstanceOf[RideHailAgentData] replying ModifyPassengerScheduleAck(
          requestId,
          triggerToSchedule,
          vehicle.id,
          tick,
          triggerId
        )
      }
    case ev @ Event(Resume(_), _) =>
      log.debug("state(RideHailingAgent.IdleInterrupted): {}", ev)
      goto(Idle)
    case ev @ Event(Interrupt(interruptId, tick, triggerId), _) =>
      log.debug("state(RideHailingAgent.IdleInterrupted): {}", ev)
      stay() replying InterruptedWhileIdle(interruptId, vehicle.id, latestObservedTick, triggerId)
    case ev @ Event(
          reply @ NotifyVehicleResourceIdleReply(_, _, _),
          data
        ) =>
      log.debug("state(RideHailingAgent.IdleInterrupted.NotifyVehicleResourceIdleReply): {}", ev)
      handleNotifyVehicleResourceIdleReply(reply, data)
    case ev @ Event(TriggerWithId(StartRefuelSessionTrigger(tick), triggerId), _) =>
      log.debug("state(RideHailingAgent.IdleInterrupted.StartRefuelSessionTrigger): {}", ev)
      stash()
      stay
    case Event(TriggerWithId(EndShiftTrigger(_), _), _) =>
      stash()
      stay()
  }

  when(WaitingToDriveInterrupted) {
    case ev @ Event(ModifyPassengerSchedule(_, _, _, _), _) =>
      log.debug("state(RideHailingAgent.WaitingToDriveInterrupted): {}", ev)
      stash()
      goto(IdleInterrupted)
    case ev @ Event(TriggerWithId(StartRefuelSessionTrigger(_), _), data) =>
      log.debug("state(RideHailingAgent.WaitingToDriveInterrupted.StartRefuelSessionTrigger): {}", ev)
      data.passengerSchedule.schedule.keys.headOption.foreach { beamLeg =>
        beamLegsToIgnoreDueToNewPassengerSchedule.add(beamLeg)
      }
      stash()
      goto(OfflineInterrupted)
  }
  when(WaitingToDrive) {
    case ev @ Event(TriggerWithId(StartRefuelSessionTrigger(_), _), data) =>
      log.debug("state(RideHailingAgent.WaitingToDrive.StartRefuelSessionTrigger): {}", ev)
      data.passengerSchedule.schedule.keys.headOption.foreach { beamLeg =>
        beamLegsToIgnoreDueToNewPassengerSchedule.add(beamLeg)
      }
      stash()
      goto(Offline)
  }

  when(PassengerScheduleEmpty) {
    case ev @ Event(PassengerScheduleEmptyMessage(lastTime, _, triggerId, _), data) =>
      log.debug("state(RideHailingAgent.PassengerScheduleEmpty): {} Remaining Shifts: {}", ev, data.remainingShifts)
      if (this.vehicle.primaryFuelLevelInJoules < 0) {
        rideHailManager ! MarkVehicleBatteryDepleted(lastTime.time, this.vehicle.id)
      }
      isOnWayToParkAtStall match {
        case Some(stall) =>
          currentBeamVehicle.useParkingStall(stall)
          if (debugEnabled) outgoingMessages += ev
          parkAndStartRefueling(stall, data)
          isOnWayToParkAtStall = None
          goto(Refueling) using data
            .withPassengerSchedule(PassengerSchedule())
            .withCurrentLegPassengerScheduleIndex(0)
            .asInstanceOf[RideHailAgentData]
        case None =>
          if (!vehicle.isCAV && vehicle.isRefuelNeeded(
                beamScenario.beamConfig.beam.agentsim.agents.rideHail.human.refuelRequiredThresholdInMeters,
                beamScenario.beamConfig.beam.agentsim.agents.rideHail.human.noRefuelThresholdInMeters
              )) {
            log.debug("Empty human ridehail vehicle requesting parking stall: event = " + ev)
            rideHailManager ! NotifyVehicleOutOfService(vehicle.id, triggerId)

            requestParkingStall()

            goto(Offline) using data
              .withPassengerSchedule(PassengerSchedule())
              .withCurrentLegPassengerScheduleIndex(0)
              .asInstanceOf[RideHailAgentData]
          } else {
            if (!vehicle.isCAV) log.debug("No refueling selected for {}", vehicle)
            goto(Idle) using data
              .withPassengerSchedule(PassengerSchedule())
              .withCurrentLegPassengerScheduleIndex(0)
              .asInstanceOf[RideHailAgentData]
          }
      }
    case ev @ Event(Interrupt(_, _, _), _) =>
      log.debug("state(RideHailingAgent.PassengerScheduleEmpty): {}", ev)
      stash()
      stay()
    case ev @ Event(TriggerWithId(StartRefuelSessionTrigger(_), _), _) =>
      log.debug("state(RideHailingAgent.PassengerScheduleEmpty): {}", ev)
      stash()
      stay
  }

  when(PassengerScheduleEmptyInterrupted) {
    case ev @ Event(PassengerScheduleEmptyMessage(_, _, _, _), data) =>
      log.debug("state(RideHailingAgent.PassengerScheduleEmptyInterrupted): {}", ev)
      data.passengerSchedule.schedule.keys.headOption.foreach { beamLeg =>
        beamLegsToIgnoreDueToNewPassengerSchedule.add(beamLeg)
      }
      goto(IdleInterrupted) using data
        .withPassengerSchedule(PassengerSchedule())
        .withCurrentLegPassengerScheduleIndex(0)
        .asInstanceOf[RideHailAgentData]
    case ev @ Event(ModifyPassengerSchedule(_, _, _, _), _) =>
      log.debug("state(RideHailingAgent.PassengerScheduleEmptyInterrupted): {}", ev)
      stash()
      stay()
    case ev @ Event(Resume(_), _) =>
      log.debug("state(RideHailingAgent.PassengerScheduleEmptyInterrupted): {}", ev)
      stash()
      stay()
    case ev @ Event(Interrupt(_, _, _), _) =>
      log.debug("state(RideHailingAgent.PassengerScheduleEmptyInterrupted): {}", ev)
      stash()
      stay()
    case ev @ Event(TriggerWithId(StartRefuelSessionTrigger(_), _), _) =>
      log.debug("state(RideHailingAgent.PassengerScheduleEmptyInterrupted): {}", ev)
      stash()
      stay
  }
  when(InQueue) {
    case Event(TriggerWithId(StartRefuelSessionTrigger(_), _), _) =>
      isInQueueParkingZoneId = None
      stash
      goto(Offline)
    case ev @ Event(Interrupt, _) =>
      goto(InQueueInterrupted)
    case ev @ Event(_, _) =>
      myUnhandled(ev)
  }
  when(InQueueInterrupted) {
    case ev @ Event(Resume(_), _) =>
      goto(InQueue)
    case ev @ Event(_, _) =>
      stash
      stay
  }
  when(Refueling) {
    case ev @ Event(Interrupt(interruptId, tick, triggerId), _) =>
      log.debug("state(RideHailingAgent.Refueling): {}", ev)
      goto(RefuelingInterrupted) replying InterruptedWhileOffline(
        interruptId,
        vehicle.id,
        latestObservedTick,
        triggerId
      )
    case ev @ Event(Resume(_), _) =>
      log.debug("state(RideHailingAgent.Refueling): {}", ev)
      stay
    case ev @ Event(TriggerWithId(EndRefuelSessionTrigger(tick, sessionStart, energyInJoules, _), triggerId), _) =>
      updateLatestObservedTick(tick)
      log.debug("state(RideHailingAgent.Refueling.EndRefuelTrigger): {}", ev)
      holdTickAndTriggerId(tick, triggerId)
      if (debugEnabled) outgoingMessages += ev
      handleEndRefuel(energyInJoules, tick, sessionStart.toInt, triggerId)
      if (isCurrentlyOnShift && !needsToEndShift) {
        goto(Idle)
      } else {
        goto(Offline)
      }
  }
  when(RefuelingInterrupted) {
    case Event(Resume(_), _) =>
      log.debug("state(RideHailingAgent.Refueling.Resume)")
      goto(Refueling)
    case ev @ Event(_, _) =>
      stash
      stay
  }

  override def logPrefix(): String = s"RideHailAgent $id: "

  def scheduleStartLegIfFeasible(passengerSchedule: PassengerSchedule, nextLeg: BeamLeg): Vector[ScheduleTrigger] = {
    if (passengerSchedule.schedule.lastKey.endTime > lastTickOfSimulation) {
      log.warning(
        s"endTime of last leg in PassengerSchedule is past end of simulation, aborting trip with passengers: ${passengerSchedule.uniquePassengers.map(_.personId).mkString(", ")}"
      )
      Vector()
    } else {
      Vector(
        ScheduleTrigger(
          StartLegTrigger(
            nextLeg.startTime,
            nextLeg
          ),
          self
        )
      )
    }
  }

  def handleEndRefuel(energyInJoules: Double, tick: Int, sessionStart: Int, triggerId: Long): Unit = {
    vehicle.addFuel(energyInJoules)
    val refuelSessionEvent = new RefuelSessionEvent(
      tick,
      vehicle.stall.get.copy(locationUTM = beamServices.geo.utm2Wgs(vehicle.stall.get.locationUTM)),
      energyInJoules,
      vehicle.primaryFuelLevelInJoules - energyInJoules,
      tick - sessionStart,
      vehicle.id,
      vehicle.beamVehicleType,
      if (isCurrentlyOnShift) { OnShift } else { OffShift }
    )
    lastLocationOfRefuel = Some(vehicle.stall.get.locationUTM)
    eventsManager.processEvent(refuelSessionEvent)
    val newLocation = vehicle.stall match {
      case None =>
        log.warning(s"ended refueling but vehicle ${vehicle.id} has no stall")
        vehicle.spaceTime.loc
      case Some(parkingStall) =>
        val cost = parkingStall.costInDollars
        eventsManager
          .processEvent(
            LeavingParkingEvent(
              tick,
              vehicle.stall.get,
              cost,
              driverId = id.toString,
              currentBeamVehicle.id
            )
          )
        if (!vehicle.isCAV) {
          val stall = vehicle.stall.get
          parkingManager ! ReleaseParkingStall(stall, triggerId)
        }
        val currentLocation = parkingStall.locationUTM
        if (!vehicle.isCAV) vehicle.unsetParkingStall()
        currentLocation
    }
    vehicle.spaceTime = SpaceTime(newLocation, tick)
    if (isCurrentlyOnShift && !needsToEndShift) {
      nextNotifyVehicleResourceIdle = Some(
        NotifyVehicleIdle(
          vehicle.id,
          vehicle.spaceTime,
          PassengerSchedule(),
          vehicle.getState,
          geofence,
          getCurrentTriggerIdOrGenerate,
        )
      )
    } else {
      waitingForDoneRefuelingAndOutOfServiceReply = true
      if (debugEnabled)
        outgoingMessages += NotifyVehicleDoneRefuelingAndOutOfService(
          vehicle.id,
          vehicle.spaceTime,
          _currentTriggerId.get,
          _currentTick.get,
          vehicle.getState
        )
      vehicle.getManager.get ! NotifyVehicleDoneRefuelingAndOutOfService(
        vehicle.id,
        vehicle.spaceTime,
        _currentTriggerId.get,
        _currentTick.get,
        vehicle.getState
      )
    }
  }

  def parkAndStartRefueling(stall: ParkingStall, data: RideHailAgentData): Unit = {
    val (tick, triggerId) = releaseTickAndTriggerId()
    eventsManager.processEvent(
      ParkingEvent(tick, stall, geo.utm2Wgs(stall.locationUTM), currentBeamVehicle.id, id.toString)
    )
    log.debug("Refuel started at {}, triggerId: {}", tick, triggerId)
    startRefueling(tick, triggerId, data)
  }

  def startRefueling(tick: Int, triggerId: Long, data: RideHailAgentData): Unit = {
    if (vehicle.isBEV || vehicle.isPHEV) {
      handleStartCharging(tick, vehicle)
    }
    handleStartRefuel(tick, triggerId, data)
  }

  def requestParkingStall(): Unit = {
    val rideHailAgentLocation =
      RideHailAgentLocation(vehicle.getDriver.get, vehicle.id, vehicle.beamVehicleType, vehicle.spaceTime, geofence)
    val destinationUtm = rideHailAgentLocation.getCurrentLocationUTM(vehicle.spaceTime.time, beamServices)
    val time = Math.max(vehicle.spaceTime.time, rideHailAgentLocation.latestUpdatedLocationUTM.time)
    val inquiry = ParkingInquiry(
      SpaceTime(destinationUtm, time),
      "fast-charge",
      beamVehicle = Some(vehicle),
      triggerId = getCurrentTriggerIdOrGenerate
    )
    parkingManager ! inquiry
  }

  def handleStartRefuel(tick: Int, triggerId: Long, data: RideHailAgentData): Unit = {
    val (unlimitedSessionDuration, _) = vehicle.refuelingSessionDurationAndEnergyInJoules(None, None, None)
    val secondsUntilEndOfSim = lastTickOfSimulation - 1 - tick
    val sessionDurationLimit = (isCurrentlyOnShift || isStartingNewShift) match {
      case false if data.remainingShifts.nonEmpty =>
        Some(
          Math.min(
            secondsUntilEndOfSim,
            Math.max(
              data.remainingShifts.head.range.lowerBound - tick - beamScenario.beamConfig.beam.agentsim.schedulerParallelismWindow,
              0
            )
          )
        )
      case _ if unlimitedSessionDuration > secondsUntilEndOfSim =>
        Some(secondsUntilEndOfSim)
      case _ =>
        None
    }
    val (sessionDuration, energyDelivered) =
      vehicle.refuelingSessionDurationAndEnergyInJoules(
        sessionDurationLimit = sessionDurationLimit,
        stateOfChargeLimit = None,
        chargingPowerLimit = None
      )

//    if(sessionDuration==0 && !vehicle.stall.get.chargingPointType.get.toString.equals("abb_50kw_dc(50.0|DC)")){
//      log.warning(
//        "scheduling EndRefuelSessionTrigger at {} with {} J to be delivered, triggerId: {}",
//        tick + sessionDuration.toInt,
//        energyDelivered,
//        triggerId
//      )
//    }
    log.debug(
      "scheduling EndRefuelSessionTrigger at {} with {} J to be delivered, triggerId: {}",
      tick + sessionDuration.toInt,
      energyDelivered,
      triggerId
    )
    if (debugEnabled)
      outgoingMessages += CompletionNotice(
        triggerId,
        Vector(
          ScheduleTrigger(EndRefuelSessionTrigger(tick + sessionDuration.toInt, tick, energyDelivered, vehicle), self)
        )
      )
    scheduler ! CompletionNotice(
      triggerId,
      Vector(
        ScheduleTrigger(EndRefuelSessionTrigger(tick + sessionDuration.toInt, tick, energyDelivered, vehicle), self)
      )
    )
  }

  def handleNotifyVehicleResourceIdleReply(
    ev: NotifyVehicleResourceIdleReply,
    data: RideHailAgentData
  ): FSM.State[BeamAgentState, RideHailAgentData] = {
    log.debug("state(RideHailingAgent.IdleInterrupted.NotifyVehicleResourceIdleReply): {}", ev)
    if (debugEnabled) outgoingMessages += ev
    val nextState = ev.vehicleInQueueParkingZoneId match {
      case Some(zoneId) =>
        isInQueueParkingZoneId = Some(zoneId)
        stateName match {
          case Offline | Idle =>
            InQueue
          case IdleInterrupted =>
            InQueueInterrupted
          case _ =>
            logError(
              s"Unexpected state $stateName for handling a NotifyVehicleResourceIdleReply assuming non-interrupted"
            )
            InQueue
        }
      case None =>
        stateName // i.e. "stay"
    }
    data.remainingShifts.size match {
      case nShifts if nShifts > 0 & isStartingNewShift =>
        val tickToSchedule = Math.min(data.remainingShifts.head.range.upperBound, lastTickOfSimulation)
        completeHandleNotifyVehicleResourceIdleReply(
          Some(ev.triggerId),
          ev.newTriggers :+ ScheduleTrigger(
            EndShiftTrigger(Math.max(tickToSchedule, _currentTick.get)),
            self
          )
        )
        isCurrentlyOnShift = true
        isStartingNewShift = false
        goto(nextState) using data.copy(remainingShifts = data.remainingShifts.tail)
      case _ =>
        completeHandleNotifyVehicleResourceIdleReply(Some(ev.triggerId), ev.newTriggers)
        goto(nextState)
    }
  }

  def completeHandleNotifyVehicleResourceIdleReply(
    receivedtriggerId: Option[Long],
    newTriggers: Seq[ScheduleTrigger]
  ): Unit = {
    _currentTriggerId match {
      case Some(_) =>
        val (tick, triggerId) = releaseTickAndTriggerId()
        if (receivedtriggerId.isEmpty || triggerId != receivedtriggerId.get) {
          log.error(
            "RHA {}: local triggerId {} does not match the id received from RHM {}",
            id,
            triggerId,
            receivedtriggerId
          )
        }
        log.debug("RHA {}: completing trigger @ {} and scheduling {}", id, tick, newTriggers)
        if (debugEnabled) outgoingMessages += CompletionNotice(triggerId, newTriggers)
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
          if (!_currentTriggerId.contains(nextIdle.triggerId)) {
            log.error(
              "RHA {}: _currentTriggerId({}) and nextNotifyVehicleResourceIdle.triggerId({}) don't match - vehicleId({})",
              id,
              _currentTriggerId,
              nextIdle.triggerId,
              vehicle.id
            )
          }
          // Only tell RHM I am Idle if I don't need to end my shift,
          // otherwise reschedule EndShiftTrigger for now to initiate
          if (debugEnabled) outgoingMessages += "TransitionToIdle"
          needsToEndShift match {
            case true =>
              val (tick, triggerId) = releaseTickAndTriggerId()
              if (debugEnabled)
                outgoingMessages += CompletionNotice(triggerId, Vector(ScheduleTrigger(EndShiftTrigger(tick), self)))
              scheduler ! CompletionNotice(triggerId, Vector(ScheduleTrigger(EndShiftTrigger(tick), self)))
              needsToEndShift = false
            case false =>
              if (debugEnabled) outgoingMessages += nextIdle
              vehicle.getManager.get ! nextIdle
          }
        case None =>
      }

      nextNotifyVehicleResourceIdle = None

    case _ -> _ =>
      unstashAll()

  }

}
