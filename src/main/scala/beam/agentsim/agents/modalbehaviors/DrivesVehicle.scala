package beam.agentsim.agents.modalbehaviors

import akka.actor.FSM.Failure
import akka.actor.{ActorRef, Stash}
import beam.agentsim.Resource.{NotifyVehicleIdle, ReleaseParkingStall}
import beam.agentsim.agents.BeamAgent
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.agents.modalbehaviors.DrivesVehicle._
import beam.agentsim.agents.ridehail.RideHailAgent._
import beam.agentsim.agents.ridehail.RideHailUtils
import beam.agentsim.agents.vehicles.AccessErrorCodes.VehicleFullError
import beam.agentsim.agents.vehicles.BeamVehicle.BeamVehicleState
import beam.agentsim.agents.vehicles.VehicleProtocol._
import beam.agentsim.agents.vehicles._
import beam.agentsim.events.{ParkEvent, PathTraversalEvent, SpaceTime}
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{TRANSIT, WALK}
import beam.router.model.BeamLeg
import beam.router.osm.TollCalculator
import beam.sim.HasServices
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.{
  LinkEnterEvent,
  LinkLeaveEvent,
  VehicleEntersTrafficEvent,
  VehicleLeavesTrafficEvent
}
import org.matsim.api.core.v01.population.Person
import org.matsim.vehicles.Vehicle

import scala.collection.mutable

/**
  * @author dserdiuk on 7/29/17.
  */
object DrivesVehicle {

  sealed trait VehicleOrToken {
    def id: Id[BeamVehicle]
    def streetVehicle: StreetVehicle
  }
  case class ActualVehicle(vehicle: BeamVehicle) extends VehicleOrToken {
    override def id: Id[BeamVehicle] = vehicle.id
    override def streetVehicle: StreetVehicle = vehicle.toStreetVehicle
  }
  case class Token(override val id: Id[BeamVehicle], manager: ActorRef, override val streetVehicle: StreetVehicle)
      extends VehicleOrToken

  case class StartLegTrigger(tick: Int, beamLeg: BeamLeg) extends Trigger

  case class EndLegTrigger(tick: Int) extends Trigger

  case class AlightVehicleTrigger(tick: Int, vehicleId: Id[Vehicle], vehicleTypeId: Option[Id[BeamVehicleType]] = None)
      extends Trigger

  case class BoardVehicleTrigger(tick: Int, vehicleId: Id[Vehicle], vehicleTypeId: Option[Id[BeamVehicleType]] = None)
      extends Trigger

  case class StopDriving(tick: Int)

  case class StartRefuelTrigger(tick: Int) extends Trigger

  case class EndRefuelTrigger(tick: Int, sessionStart: Double, fuelAddedInJoule: Double) extends Trigger

  case class BeamVehicleStateUpdate(id: Id[Vehicle], vehicleState: BeamVehicleState)

  case class StopDrivingIfNoPassengerOnBoard(tick: Int, requestId: Int)

  case class StopDrivingIfNoPassengerOnBoardReply(success: Boolean, requestId: Int, tick: Int)

}

trait DrivesVehicle[T <: DrivingData] extends BeamAgent[T] with HasServices with Stash {

  protected val transportNetwork: TransportNetwork
  protected val parkingManager: ActorRef
  protected val tollCalculator: TollCalculator
  private var tollsAccumulated = 0.0
  protected val beamVehicles: mutable.Map[Id[BeamVehicle], VehicleOrToken] = mutable.Map()
  protected def currentBeamVehicle = beamVehicles(stateData.currentVehicle.head).asInstanceOf[ActualVehicle].vehicle

  case class PassengerScheduleEmptyMessage(lastVisited: SpaceTime, toll: Double)

  private def handleStopDrivingIfNoPassengerOnBoard(
    tick: Int,
    requestId: Int,
    data: T
  ): State = {
    println("handleStopDrivingIfNoPassengerOnBoard:" + stateName)
    data.passengerSchedule.schedule.keys
      .drop(data.currentLegPassengerScheduleIndex)
      .headOption match {
      case Some(currentLeg) =>
        println(currentLeg)
        if (data.passengerSchedule.schedule(currentLeg).riders.isEmpty) {
          log.info("stopping vehicle: {}", id)
          goto(DrivingInterrupted) replying StopDrivingIfNoPassengerOnBoardReply(
            success = true,
            requestId,
            tick
          )
        } else {
          stay() replying StopDrivingIfNoPassengerOnBoardReply(success = false, requestId, tick)
        }
      case None =>
        stay()
    }
  }

  var nextNotifyVehicleResourceIdle: Option[NotifyVehicleIdle] = None

  when(Driving) {
    case ev @ Event(
          TriggerWithId(EndLegTrigger(tick), triggerId),
          LiterallyDrivingData(data, legEndingAt)
        ) if tick == legEndingAt =>
//      log.debug("state(DrivesVehicle.Driving): {}", ev)
      log.debug("state(DrivesVehicle.Driving): EndLegTrigger({}) for driver {}", tick, id)
      val currentLeg = data.passengerSchedule.schedule.keys.view
        .drop(data.currentLegPassengerScheduleIndex)
        .headOption
        .getOrElse(throw new RuntimeException("Current Leg is not available."))
      val currentVehicleUnderControl = data.currentVehicle.headOption
        .getOrElse(throw new RuntimeException("Current Vehicle is not available."))
      val isLastLeg = data.currentLegPassengerScheduleIndex + 1 == data.passengerSchedule.schedule.size
      val fuelConsumed = currentBeamVehicle.useFuel(currentLeg, beamServices)

      if (isLastLeg) {
        nextNotifyVehicleResourceIdle = Some(
          NotifyVehicleIdle(
            currentVehicleUnderControl,
            beamServices.geo.wgs2Utm(currentLeg.travelPath.endPoint),
            data.passengerSchedule,
            currentBeamVehicle.getState,
            Some(triggerId)
          )
        )
      }
//      log.debug(
//        "DrivesVehicle.Driving.nextNotifyVehicleResourceIdle:{}, vehicleId({}) - tick({})",
//        nextNotifyVehicleResourceIdle,
//        currentVehicleUnderControl,
//        tick
//      )

      data.passengerSchedule.schedule(currentLeg).alighters.foreach { pv =>
        logDebug(s"Scheduling AlightVehicleTrigger for Person $pv.personRef @ $tick")
        scheduler ! ScheduleTrigger(
          AlightVehicleTrigger(tick, data.currentVehicle.head, Some(currentBeamVehicle.beamVehicleType.id)),
          pv.personRef
        )
      }

      // EventsToLegs fails for our way of reporting e.g. walk/car/walk trips,
      // or any trips with multiple link-based vehicles where there isn't an
      // activity in between.
      // We help ourselves by not emitting link events for walking, but a better criterion
      // would be to only emit link events for the "main" leg.
      if (currentLeg.mode != WALK) {
        processLinkEvents(data.currentVehicle.head, currentLeg)
      }

      logDebug("PathTraversal")
      eventsManager.processEvent(
        new VehicleLeavesTrafficEvent(
          tick,
          id.asInstanceOf[Id[Person]],
          Id.createLinkId(currentLeg.travelPath.linkIds.lastOption.getOrElse(Int.MinValue).toString),
          data.currentVehicle.head,
          "car",
          0.0
        )
      )

      val tollOnCurrentLeg = toll(currentLeg)
      tollsAccumulated += tollOnCurrentLeg
      eventsManager.processEvent(
        PathTraversalEvent(
          tick,
          currentVehicleUnderControl,
          id.toString,
          currentBeamVehicle.beamVehicleType,
          data.passengerSchedule.schedule(currentLeg).riders.size,
          currentLeg,
          fuelConsumed.primaryFuel,
          fuelConsumed.secondaryFuel,
          currentBeamVehicle.primaryFuelLevelInJoules,
          currentBeamVehicle.secondaryFuelLevelInJoules,
          tollOnCurrentLeg
        )
      )

      if (!isLastLeg) {
        if (data.hasParkingBehaviors) {
          holdTickAndTriggerId(tick, triggerId)
          goto(ReadyToChooseParking) using data
            .withCurrentLegPassengerScheduleIndex(data.currentLegPassengerScheduleIndex + 1)
            .asInstanceOf[T]
        } else {
          val nextLeg =
            data.passengerSchedule.schedule.keys.view
              .drop(data.currentLegPassengerScheduleIndex + 1)
              .head
          goto(WaitingToDrive) using data
            .withCurrentLegPassengerScheduleIndex(data.currentLegPassengerScheduleIndex + 1)
            .asInstanceOf[T] replying CompletionNotice(
            triggerId,
            Vector(ScheduleTrigger(StartLegTrigger(nextLeg.startTime, nextLeg), self))
          )
        }
      } else {
        if (data.hasParkingBehaviors) {
          currentBeamVehicle.reservedStall.foreach { stall =>
            currentBeamVehicle.useParkingStall(stall)
            eventsManager.processEvent(ParkEvent(tick, stall, currentBeamVehicle.id, id.toString)) // nextLeg.endTime -> to fix repeated path traversal
          }
          currentBeamVehicle.setReservedParkingStall(None)
        }
        holdTickAndTriggerId(tick, triggerId)
        self ! PassengerScheduleEmptyMessage(
          beamServices.geo.wgs2Utm(
            data.passengerSchedule.schedule
              .drop(data.currentLegPassengerScheduleIndex)
              .head
              ._1
              .travelPath
              .endPoint
          ),
          tollsAccumulated
        )
        tollsAccumulated = 0.0
        goto(PassengerScheduleEmpty) using data
          .withCurrentLegPassengerScheduleIndex(data.currentLegPassengerScheduleIndex + 1)
          .asInstanceOf[T]
      }

    case ev @ Event(TriggerWithId(EndLegTrigger(tick), triggerId), data) =>
      log.debug("state(DrivesVehicle.Driving): {}", ev)

      log.debug(
        "DrivesVehicle.IgnoreEndLegTrigger: vehicleId({}), tick({}), triggerId({}), data({})",
        id,
        tick,
        triggerId,
        data
      )
      stay replying CompletionNotice(triggerId, Vector())

    case ev @ Event(Interrupt(interruptId, tick), data) =>
      log.debug("state(DrivesVehicle.Driving): {}", ev)
      goto(DrivingInterrupted) replying InterruptedWhileDriving(
        interruptId,
        currentBeamVehicle.id,
        tick,
        data.passengerSchedule,
        data.currentLegPassengerScheduleIndex
      )

    case ev @ Event(StopDrivingIfNoPassengerOnBoard(tick, requestId), data) =>
      log.debug("state(DrivesVehicle.Driving): {}", ev)
      data.passengerSchedule.schedule.keys.view
        .drop(data.currentLegPassengerScheduleIndex)
        .headOption match {
        case Some(currentLeg) =>
          if (data.passengerSchedule.schedule(currentLeg).riders.isEmpty) {
            log.info("stopping vehicle: {}", id)
            goto(DrivingInterrupted) replying StopDrivingIfNoPassengerOnBoardReply(
              success = true,
              requestId,
              tick
            )

          } else {
            stay() replying StopDrivingIfNoPassengerOnBoardReply(success = false, requestId, tick)
          }
        case None =>
          stay()
      }
  }

  when(DrivingInterrupted) {
    case ev @ Event(StopDriving(stopTick), LiterallyDrivingData(data, _)) =>
      log.debug("state(DrivesVehicle.DrivingInterrupted): {}", ev)
      val currentLeg = data.passengerSchedule.schedule.keys.view
        .drop(data.currentLegPassengerScheduleIndex)
        .headOption
        .getOrElse(throw new RuntimeException("Current Leg is not available."))
      val currentVehicleUnderControl = data.currentVehicle.headOption
        .getOrElse(throw new RuntimeException("Current Vehicle is not available."))

      if (data.passengerSchedule.schedule(currentLeg).riders.nonEmpty) {
        log.error("DrivingInterrupted.StopDriving.Vehicle: " + data.currentVehicle.head)
        log.error("DrivingInterrupted.StopDriving.PassengerSchedule: " + data.passengerSchedule)
      }

      assert(data.passengerSchedule.schedule(currentLeg).riders.isEmpty)
      val updatedBeamLeg =
        RideHailUtils.getUpdatedBeamLegAfterStopDriving(
          currentLeg,
          stopTick,
          transportNetwork
        )

      val fuelConsumed = currentBeamVehicle.useFuel(updatedBeamLeg, beamServices)

      nextNotifyVehicleResourceIdle = Some(
        NotifyVehicleIdle(
          currentVehicleUnderControl,
          beamServices.geo.wgs2Utm(updatedBeamLeg.travelPath.endPoint),
          data.passengerSchedule,
          currentBeamVehicle.getState,
          _currentTriggerId
        )
      )

//      log.debug(
//        "DrivesVehicle.DrivingInterrupted.nextNotifyVehicleResourceIdle:{}",
//        nextNotifyVehicleResourceIdle
//      )

      eventsManager.processEvent(
        new VehicleLeavesTrafficEvent(
          stopTick,
          id.asInstanceOf[Id[Person]],
          null,
          data.currentVehicle.head,
          "car",
          0.0
        )
      )

      val tollOnCurrentLeg = toll(currentLeg)
      tollsAccumulated += tollOnCurrentLeg
      eventsManager.processEvent(
        PathTraversalEvent(
          stopTick,
          currentVehicleUnderControl,
          id.toString,
          currentBeamVehicle.beamVehicleType,
          data.passengerSchedule.schedule(currentLeg).riders.size,
          updatedBeamLeg,
          fuelConsumed.primaryFuel,
          fuelConsumed.secondaryFuel,
          currentBeamVehicle.primaryFuelLevelInJoules,
          currentBeamVehicle.secondaryFuelLevelInJoules,
          tollOnCurrentLeg
        )
      )

      self ! PassengerScheduleEmptyMessage(
        beamServices.geo.wgs2Utm(
          data.passengerSchedule.schedule
            .drop(data.currentLegPassengerScheduleIndex)
            .head
            ._1
            .travelPath
            .endPoint
        ),
        tollsAccumulated
      )
      tollsAccumulated = 0.0
      goto(PassengerScheduleEmptyInterrupted) using data
        .withCurrentLegPassengerScheduleIndex(data.currentLegPassengerScheduleIndex + 1)
        .asInstanceOf[T]
    case ev @ Event(Resume(), _) =>
      log.debug("state(DrivesVehicle.DrivingInterrupted): {}", ev)
      goto(Driving)
    case ev @ Event(TriggerWithId(EndLegTrigger(_), _), _) =>
      log.debug("state(DrivesVehicle.DrivingInterrupted): {}", ev)
      stash()
      stay
    case ev @ Event(Interrupt(_, _), _) =>
      log.debug("state(DrivesVehicle.DrivingInterrupted): {}", ev)
      stash()
      stay
  }

  when(WaitingToDrive) {
    case ev @ Event(TriggerWithId(StartLegTrigger(tick, newLeg), triggerId), data) =>
//      log.debug("state(DrivesVehicle.WaitingToDrive): {}", ev)
      log.debug("state(DrivesVehicle.WaitingToDrive): StartLegTrigger({},{}) for driver {}", tick, newLeg, id)

      if (data.currentVehicle.isEmpty) {
        stop(Failure("person received StartLegTrigger for leg {} but has an empty data.currentVehicle", newLeg))
      } else {
        // Un-Park if necessary, this should only happen with RideHailAgents
        data.currentVehicle.headOption match {
          case Some(currentVehicleUnderControl) =>
            assert(
              currentBeamVehicle.id == currentVehicleUnderControl,
              currentBeamVehicle.id + " " + currentVehicleUnderControl
            )
            currentBeamVehicle.stall.foreach { theStall =>
              parkingManager ! ReleaseParkingStall(theStall.id)
            }
            currentBeamVehicle.unsetParkingStall()
          case None =>
        }
        val triggerToSchedule: Vector[ScheduleTrigger] = data.passengerSchedule
          .schedule(newLeg)
          .boarders
          .map { personVehicle =>
            logDebug(
              s"Scheduling BoardVehicleTrigger at $tick for Person ${personVehicle.personId} into vehicle ${data.currentVehicle.head}"
            )
            ScheduleTrigger(
              BoardVehicleTrigger(tick, data.currentVehicle.head, Some(currentBeamVehicle.beamVehicleType.id)),
              personVehicle.personRef
            )
          }
          .toVector
        eventsManager.processEvent(
          new VehicleEntersTrafficEvent(
            tick,
            Id.createPersonId(id),
            Id.createLinkId(newLeg.travelPath.linkIds.headOption.getOrElse(Int.MinValue).toString),
            data.currentVehicle.head,
            "car",
            1.0
          )
        )
        // Produce link events for this trip (the same ones as in PathTraversalEvent).
        val beamLeg = data.passengerSchedule.schedule
          .drop(data.currentLegPassengerScheduleIndex)
          .head
          ._1
        val endTime = tick + beamLeg.duration
        goto(Driving) using LiterallyDrivingData(data, endTime)
          .asInstanceOf[T] replying CompletionNotice(
          triggerId,
          triggerToSchedule ++ Vector(ScheduleTrigger(EndLegTrigger(endTime), self))
        )
      }
    case ev @ Event(Interrupt(_, _), _) =>
      log.debug("state(DrivesVehicle.WaitingToDrive): {}", ev)
      stash()
      stay

    case ev @ Event(
          NotifyVehicleResourceIdleReply(
            triggerId: Option[Long],
            newTriggers: Seq[ScheduleTrigger]
          ),
          _
        ) =>
      log.debug("state(DrivesVehicle.WaitingToDrive.NotifyVehicleResourceIdleReply): {}", ev)

      if (triggerId != _currentTriggerId) {
        log.error(
          "Driver {}: local triggerId {} does not match the id received from resource manager {}",
          id,
          _currentTriggerId,
          triggerId
        )
      }

      _currentTriggerId match {
        case Some(_) =>
          val (_, triggerId) = releaseTickAndTriggerId()
          scheduler ! CompletionNotice(triggerId, newTriggers)
        case None =>
      }

      stay()

    case Event(StopDrivingIfNoPassengerOnBoard(tick, requestId), data) =>
      handleStopDrivingIfNoPassengerOnBoard(tick, requestId, data)

  }

  when(WaitingToDriveInterrupted) {
    case ev @ Event(Resume(), _) =>
      log.debug("state(DrivesVehicle.WaitingToDriveInterrupted): {}", ev)
      goto(WaitingToDrive)

    case ev @ Event(TriggerWithId(StartLegTrigger(_, _), _), _) =>
      log.debug("state(DrivesVehicle.WaitingToDriveInterrupted): {}", ev)
      stash()
      stay

  }

  val drivingBehavior: StateFunction = {
    case ev @ Event(req: ReservationRequest, data)
        if !hasRoomFor(
          data.passengerSchedule,
          req,
          currentBeamVehicle
        ) =>
      log.debug("state(DrivesVehicle.drivingBehavior): {}", ev)
      stay() replying ReservationResponse(req.requestId, Left(VehicleFullError), TRANSIT)

    case ev @ Event(req: ReservationRequest, data) =>
      log.debug("state(DrivesVehicle.drivingBehavior): {}", ev)
      val legs = data.passengerSchedule.schedule
        .from(req.departFrom)
        .to(req.arriveAt)
        .keys
        .toSeq
      val legsInThePast = data.passengerSchedule.schedule
        .take(data.currentLegPassengerScheduleIndex)
        .from(req.departFrom)
        .to(req.arriveAt)
        .keys
        .toSeq
      if (legsInThePast.nonEmpty)
        log.debug("Legs in the past: {} -- {}", legsInThePast, req)
      val boardTrigger = if (legsInThePast.nonEmpty) {
        Vector(
          ScheduleTrigger(
            BoardVehicleTrigger(
              legsInThePast.head.startTime,
              data.currentVehicle.head,
              Some(currentBeamVehicle.beamVehicleType.id)
            ),
            sender()
          )
        )
      } else {
        Vector()
      }
      val alightTrigger = if (legsInThePast.nonEmpty && legsInThePast.size == legs.size) {
        Vector(
          ScheduleTrigger(
            AlightVehicleTrigger(
              legsInThePast.last.endTime,
              data.currentVehicle.head,
              Some(currentBeamVehicle.beamVehicleType.id)
            ),
            sender()
          )
        )
      } else {
        Vector()
      }

      val boardTrigger2 = data.passengerSchedule.schedule.keys.view
        .drop(data.currentLegPassengerScheduleIndex)
        .headOption match {
        case Some(currentLeg) =>
          if (stateName == Driving && legs.head == currentLeg) {
            Vector(
              ScheduleTrigger(
                BoardVehicleTrigger(
                  currentLeg.startTime,
                  data.currentVehicle.head,
                  Some(currentBeamVehicle.beamVehicleType.id)
                ),
                sender()
              )
            )
          } else {
            Vector()
          }
        case None =>
          log.warning("Driver did not find a leg at currentLegPassengerScheduleIndex.")
          Vector()
      }
      stay() using data
        .withPassengerSchedule(
          data.passengerSchedule.addPassenger(req.passengerVehiclePersonId, legs)
        )
        .asInstanceOf[T] replying
      ReservationResponse(
        req.requestId,
        Right(
          ReserveConfirmInfo(
            req.departFrom,
            req.arriveAt,
            req.passengerVehiclePersonId,
            boardTrigger ++ alightTrigger ++ boardTrigger2
          )
        ),
        TRANSIT
      )

    case ev @ Event(RemovePassengerFromTrip(id), data) =>
      log.debug("state(DrivesVehicle.drivingBehavior): {}", ev)
      stay() using data
        .withPassengerSchedule(
          PassengerSchedule(
            data.passengerSchedule.schedule ++ data.passengerSchedule.schedule
              .collect {
                case (leg, manifest) =>
                  (
                    leg,
                    manifest.copy(
                      riders = manifest.riders - id,
                      alighters = manifest.alighters - id,
                      boarders = manifest.boarders - id
                    )
                  )
              }
          )
        )
        .asInstanceOf[T]

    case Event(StopDrivingIfNoPassengerOnBoard(tick, requestId), data) =>
      log.debug("DrivesVehicle.StopDrivingIfNoPassengerOnBoard -> unhandled + {}", stateName)

      handleStopDrivingIfNoPassengerOnBoard(tick, requestId, data)

    // The following 2 (Board and Alight) can happen idiosyncratically if a person ended up taking a much longer than expected
    // trip and meanwhile a CAV was scheduled to pick them up (and then drop them off) for the next trip, but they're still driving baby
    case Event(
        TriggerWithId(BoardVehicleTrigger(tick, vehicleId, vehicleTypeId), triggerId),
        data @ LiterallyDrivingData(_, _)
        ) =>
      val currentLeg = data.passengerSchedule.schedule.keys.view
        .drop(data.currentLegPassengerScheduleIndex)
        .headOption
        .getOrElse(throw new RuntimeException("Current Leg is not available."))
      stay() replying CompletionNotice(
        triggerId,
        Vector(ScheduleTrigger(BoardVehicleTrigger(Math.max(currentLeg.endTime,tick), vehicleId, vehicleTypeId), self))
      )
    case Event(
        TriggerWithId(AlightVehicleTrigger(tick, vehicleId, vehicleTypeId), triggerId),
        data @ LiterallyDrivingData(_, _)
        ) =>
      val currentLeg = data.passengerSchedule.schedule.keys.view
        .drop(data.currentLegPassengerScheduleIndex)
        .headOption
        .getOrElse(throw new RuntimeException("Current Leg is not available."))
      stay() replying CompletionNotice(
        triggerId,
        Vector(ScheduleTrigger(AlightVehicleTrigger(Math.max(currentLeg.endTime + 1,tick), vehicleId, vehicleTypeId), self))
      )
  }

  private def hasRoomFor(
    passengerSchedule: PassengerSchedule,
    req: ReservationRequest,
    vehicle: BeamVehicle
  ) = {
//    val vehicleCap = vehicle.getType
    val fullCap = vehicle.beamVehicleType.seatingCapacity + vehicle.beamVehicleType.standingRoomCapacity
    passengerSchedule.schedule.from(req.departFrom).to(req.arriveAt).forall { entry =>
      entry._2.riders.size < fullCap
    }
  }

  def processLinkEvents(vehicleId: Id[Vehicle], leg: BeamLeg): Unit = {
    val path = leg.travelPath
    if (path.linkTravelTime.nonEmpty) {
      // FIXME once done with debugging, make this code faster
      // We don't need the travel time for the last link, so we drop it (dropRight(1))
      val avgTravelTimeWithoutLast = path.linkTravelTime.dropRight(1)
      val links = path.linkIds
      val linksWithTime = links.sliding(2).zip(avgTravelTimeWithoutLast.iterator)

      var curTime = leg.startTime
      linksWithTime.foreach {
        case (Seq(from, to), timeAtNode) =>
          curTime = curTime + timeAtNode
          eventsManager.processEvent(new LinkLeaveEvent(curTime, vehicleId, Id.createLinkId(from)))
          eventsManager.processEvent(new LinkEnterEvent(curTime, vehicleId, Id.createLinkId(to)))
      }
    }
  }

  private def toll(leg: BeamLeg) = {
    if (leg.mode == BeamMode.CAR)
      tollCalculator.calcTollByLinkIds(leg.travelPath)
    else
      0.0
  }

}
