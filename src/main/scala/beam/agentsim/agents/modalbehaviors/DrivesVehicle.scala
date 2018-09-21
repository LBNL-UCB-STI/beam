package beam.agentsim.agents.modalbehaviors

import akka.actor.FSM.Failure
import akka.actor.{ActorRef, Stash}
import beam.agentsim.Resource.CheckInResource
import beam.agentsim.ResourceManager.NotifyVehicleResourceIdle
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
import beam.router.Modes.BeamMode.TRANSIT
import beam.router.RoutingModel
import beam.router.RoutingModel.BeamLeg
import beam.sim.HasServices
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.{VehicleEntersTrafficEvent, VehicleLeavesTrafficEvent}
import org.matsim.api.core.v01.population.Person
import org.matsim.vehicles.Vehicle

/**
  * @author dserdiuk on 7/29/17.
  */
object DrivesVehicle {

  case class StartLegTrigger(tick: Int, beamLeg: BeamLeg) extends Trigger

  case class EndLegTrigger(tick: Int) extends Trigger

  case class NotifyLegEndTrigger(tick: Int, beamLeg: BeamLeg, vehicleId: Id[Vehicle]) extends Trigger

  case class NotifyLegStartTrigger(tick: Int, beamLeg: BeamLeg, vehicleId: Id[Vehicle]) extends Trigger

  case class StopDriving(tick: Int)

  case class AddFuel(fuelInJoules: Double)

  case class StartRefuelTrigger(tick: Int) extends Trigger

  case class EndRefuelTrigger(tick: Int, sessionStart: Double, fuelAddedInJoule: Double) extends Trigger

  case class BeamVehicleStateUpdate(id: Id[Vehicle], vehicleState: BeamVehicleState)

  case class StopDrivingIfNoPassengerOnBoard(tick: Int, requestId: Int)

  case class StopDrivingIfNoPassengerOnBoardReply(success: Boolean, requestId: Int, tick: Int)

  case object GetBeamVehicleState

}

trait DrivesVehicle[T <: DrivingData] extends BeamAgent[T] with HasServices with Stash {

  val drivingBehavior: StateFunction = {
    case ev@Event(req: ReservationRequest, data)
      if !hasRoomFor(
        data.passengerSchedule,
        req,
        beamServices.vehicles(data.currentVehicle.head)
      ) =>
      log.debug("state(DrivesVehicle.drivingBehavior): {}", ev)
      stay() replying ReservationResponse(req.requestId, Left(VehicleFullError), TRANSIT)

    case ev@Event(req: ReservationRequest, data) =>
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
      val triggersToSchedule = legsInThePast
        .flatMap(
          leg =>
            Vector(
              ScheduleTrigger(
                NotifyLegStartTrigger(leg.startTime, leg, data.currentVehicle.head),
                sender()
              ),
              ScheduleTrigger(
                NotifyLegEndTrigger(leg.endTime, leg, data.currentVehicle.head),
                sender()
              )
            )
        )
        .toVector
      val triggersToSchedule2 = data.passengerSchedule.schedule.keys.view
        .drop(data.currentLegPassengerScheduleIndex)
        .headOption match {
        case Some(currentLeg) =>
          if (stateName == Driving && legs.contains(currentLeg)) {
            Vector(
              ScheduleTrigger(
                NotifyLegStartTrigger(currentLeg.startTime, currentLeg, data.currentVehicle.head),
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
              triggersToSchedule ++ triggersToSchedule2
            )
          ),
          TRANSIT
        )

    case ev@Event(RemovePassengerFromTrip(id), data) =>
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
                      alighters = manifest.alighters - id.vehicleId,
                      boarders = manifest.boarders - id.vehicleId
                    )
                  )
              }
          )
        )
        .asInstanceOf[T]

    case ev@Event(AddFuel(fuelInJoules), data) =>
      log.debug("state(DrivesVehicle.drivingBehavior): {}", ev)
      val currentVehicleUnderControl =
        beamServices.vehicles(data.currentVehicle.head)
      currentVehicleUnderControl.addFuel(fuelInJoules)
      stay()

    case ev@Event(GetBeamVehicleState, data) =>
      log.debug("state(DrivesVehicle.drivingBehavior): {}", ev)
      // val currentLeg = data.passengerSchedule.schedule.keys.drop(data.currentLegPassengerScheduleIndex).head
      // as fuel is updated only at end of leg, might not be fully accurate - if want to do more accurate, will need to update fuel during leg
      // also position is not accurate (TODO: interpolate?)
      val currentVehicleUnderControl =
      beamServices.vehicles(data.currentVehicle.head)

      //      val lastLocationVisited = SpaceTime(new Coord(0, 0), 0) // TODO: don't ask for this here - TNC should keep track of it?
      // val lastLocationVisited = currentLeg.travelPath.endPoint

      sender() ! BeamVehicleStateUpdate(
        currentVehicleUnderControl.id,
        currentVehicleUnderControl.getState()
      )
      stay()

    case Event(StopDrivingIfNoPassengerOnBoard(tick, requestId), data) =>
      println(s"DrivesVehicle.StopDrivingIfNoPassengerOnBoard -> unhandled + $stateName")

      handleStopDrivingIfNoPassengerOnBoard(tick, requestId, data)
    //stay()

  }
  protected val transportNetwork: TransportNetwork
  protected val parkingManager: ActorRef
  var nextNotifyVehicleResourceIdle: Option[NotifyVehicleResourceIdle] = None

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
          log.info(s"stopping vehicle: $id")
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

  when(Driving) {
    case ev@Event(
    TriggerWithId(EndLegTrigger(tick), triggerId),
    LiterallyDrivingData(data, legEndingAt)
    ) if tick == legEndingAt =>
      log.debug("state(DrivesVehicle.Driving): {}", ev)

      val isLastLeg = data.currentLegPassengerScheduleIndex + 1 == data.passengerSchedule.schedule.size
      data.currentVehicle.headOption match {
        case Some(currentVehicleUnderControl) =>
          // If no manager is set, we ignore
          data.passengerSchedule.schedule.keys.view
            .drop(data.currentLegPassengerScheduleIndex)
            .headOption match {
            case Some(currentLeg) =>
              val fuelConsumed = beamServices
                .vehicles(currentVehicleUnderControl)
                .useFuel(currentLeg.travelPath.distanceInM)

              if (isLastLeg) {
                val theVehicle = beamServices.vehicles(currentVehicleUnderControl)
                nextNotifyVehicleResourceIdle = Some(
                  NotifyVehicleResourceIdle(
                    currentVehicleUnderControl,
                    Some(beamServices.geo.wgs2Utm(currentLeg.travelPath.endPoint)),
                    data.passengerSchedule,
                    theVehicle.getState(),
                    Some(triggerId)
                  )
                )
              }
              log.debug(
                s"DrivesVehicle.Driving.nextNotifyVehicleResourceIdle:$nextNotifyVehicleResourceIdle, vehicleId($currentVehicleUnderControl) - tick($tick)"
              )

              data.passengerSchedule.schedule(currentLeg).riders.foreach { pv =>
                beamServices.personRefs.get(pv.personId).foreach { personRef =>
                  logDebug(s"Scheduling NotifyLegEndTrigger for Person $personRef")
                  scheduler ! ScheduleTrigger(
                    NotifyLegEndTrigger(tick, currentLeg, data.currentVehicle.head),
                    personRef
                  )
                }
              }
              logDebug(s"PathTraversal")
              eventsManager.processEvent(
                new VehicleLeavesTrafficEvent(
                  tick,
                  id.asInstanceOf[Id[Person]],
                  null,
                  data.currentVehicle.head,
                  "car",
                  0.0
                )
              )
              eventsManager.processEvent(
                new PathTraversalEvent(
                  tick,
                  currentVehicleUnderControl,
                  beamServices.vehicles(currentVehicleUnderControl).beamVehicleType,
                  data.passengerSchedule.schedule(currentLeg).riders.size,
                  currentLeg,
                  fuelConsumed,
                  beamServices
                    .vehicles(currentVehicleUnderControl)
                    .fuelLevelInJoules
                    .getOrElse(-1.0)
                )
              )
            case None =>
              log.error("Current Leg is not available.")
          }
        case None =>
          log.error("Current Vehicle is not available.")
      }

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
          //Throwing parkEvent after last PathTraversal
          val vehId = data.currentVehicle.head
          val theVehicle = beamServices.vehicles(data.currentVehicle.head)
          theVehicle.reservedStall.foreach { stall =>
            theVehicle.useParkingStall(stall)
            val nextLeg =
              data.passengerSchedule.schedule.keys.view
                .drop(data.currentLegPassengerScheduleIndex)
                .head
            val distance =
              beamServices.geo.distInMeters(stall.location, nextLeg.travelPath.endPoint.loc)
            eventsManager
              .processEvent(new ParkEvent(tick, stall, distance, vehId)) // nextLeg.endTime -> to fix repeated path traversal
          }
          theVehicle.setReservedParkingStall(None)
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
          )
        )
        goto(PassengerScheduleEmpty) using data
          .withCurrentLegPassengerScheduleIndex(data.currentLegPassengerScheduleIndex + 1)
          .asInstanceOf[T]
      }

    case ev@Event(TriggerWithId(EndLegTrigger(tick), triggerId), data) =>
      log.debug("state(DrivesVehicle.Driving): {}", ev)

      log.debug(
        "DrivesVehicle.IgnoreEndLegTrigger: vehicleId({}), tick({}), triggerId({}), data({})",
        id,
        tick,
        triggerId,
        data
      )
      stay replying CompletionNotice(triggerId, Vector())

    case ev@Event(Interrupt(interruptId, tick), data) =>
      log.debug("state(DrivesVehicle.Driving): {}", ev)
      val currentVehicleUnderControl =
        beamServices.vehicles(data.currentVehicle.head)
      goto(DrivingInterrupted) replying InterruptedAt(
        interruptId,
        data.passengerSchedule,
        data.currentLegPassengerScheduleIndex,
        currentVehicleUnderControl.id,
        tick
      )

    case ev@Event(StopDrivingIfNoPassengerOnBoard(tick, requestId), data) =>
      log.debug("state(DrivesVehicle.DrivingInterrupted): {}", ev)
      data.passengerSchedule.schedule.keys.view
        .drop(data.currentLegPassengerScheduleIndex)
        .headOption match {
        case Some(currentLeg) =>
          if (data.passengerSchedule.schedule(currentLeg).riders.isEmpty) {
            log.info(s"stopping vehicle: $id")

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
    case Event(StopDrivingIfNoPassengerOnBoard(tick, requestId), data) =>
      handleStopDrivingIfNoPassengerOnBoard(tick, requestId, data)

  }

  when(DrivingInterrupted) {
    case ev@Event(StopDriving(stopTick), LiterallyDrivingData(data, _)) =>
      log.debug("state(DrivesVehicle.DrivingInterrupted): {}", ev)
      //      val isLastLeg = data.currentLegPassengerScheduleIndex + 1 == data.passengerSchedule.schedule.size
      data.passengerSchedule.schedule.keys.view
        .drop(data.currentLegPassengerScheduleIndex)
        .headOption match {
        case Some(currentLeg) =>
          if (data.passengerSchedule.schedule(currentLeg).riders.nonEmpty) {
            log.error("DrivingInterrupted.StopDriving.Vehicle: " + data.currentVehicle.head)
            log.error("DrivingInterrupted.StopDriving.PassengerSchedule: " + data.passengerSchedule)
          }

          assert(data.passengerSchedule.schedule(currentLeg).riders.isEmpty)
          data.currentVehicle.headOption match {
            case Some(currentVehicleUnderControl) =>
              val updatedBeamLeg =
                RideHailUtils.getUpdatedBeamLegAfterStopDriving(
                  currentLeg,
                  stopTick,
                  transportNetwork
                )

              val theVehicle = beamServices.vehicles(currentVehicleUnderControl)

              val fuelConsumed = theVehicle.useFuel(updatedBeamLeg.travelPath.distanceInM)

              nextNotifyVehicleResourceIdle = Some(
                NotifyVehicleResourceIdle(
                  currentVehicleUnderControl,
                  Some(beamServices.geo.wgs2Utm(updatedBeamLeg.travelPath.endPoint)),
                  data.passengerSchedule,
                  theVehicle.getState(),
                  _currentTriggerId
                )
              )

              log.debug(
                s"DrivesVehicle.DrivingInterrupted.nextNotifyVehicleResourceIdle:$nextNotifyVehicleResourceIdle"
              )

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
              eventsManager.processEvent(
                new PathTraversalEvent(
                  stopTick,
                  currentVehicleUnderControl,
                  beamServices.vehicles(currentVehicleUnderControl).beamVehicleType,
                  data.passengerSchedule.schedule(currentLeg).riders.size,
                  updatedBeamLeg,
                  fuelConsumed,
                  beamServices
                    .vehicles(currentVehicleUnderControl)
                    .fuelLevelInJoules
                    .getOrElse(-1.0)
                )
              )

            case None =>
              log.error("Current Vehicle is not available.")
          }
        case None =>
          log.error("Current Leg is not available.")
      }
      self ! PassengerScheduleEmptyMessage(
        beamServices.geo.wgs2Utm(
          data.passengerSchedule.schedule
            .drop(data.currentLegPassengerScheduleIndex)
            .head
            ._1
            .travelPath
            .endPoint
        )
      )
      goto(PassengerScheduleEmptyInterrupted) using data
        .withCurrentLegPassengerScheduleIndex(data.currentLegPassengerScheduleIndex + 1)
        .asInstanceOf[T]
    case ev@Event(Resume(), _) =>
      log.debug("state(DrivesVehicle.DrivingInterrupted): {}", ev)
      goto(Driving)
    case ev@Event(TriggerWithId(EndLegTrigger(_), _), _) =>
      log.debug("state(DrivesVehicle.DrivingInterrupted): {}", ev)
      stash()
      stay
    case ev@Event(Interrupt(_, _), _) =>
      log.debug("state(DrivesVehicle.DrivingInterrupted): {}", ev)
      stash()
      stay
  }

  when(WaitingToDrive) {
    case ev@Event(TriggerWithId(StartLegTrigger(tick, newLeg), triggerId), data) =>
      log.debug("state(DrivesVehicle.WaitingToDrive): {}", ev)

      if (data.currentVehicle.isEmpty) {
        stop(Failure("person received StartLegTrigger for leg {} but has an empty data.currentVehicle", newLeg))
      } else {
        // Un-Park if necessary, this should only happen with RideHailAgents
        data.currentVehicle.headOption match {
          case Some(currentVehicleUnderControl) =>
            val theVehicle = beamServices.vehicles(currentVehicleUnderControl)
            theVehicle.stall.foreach { theStall =>
              parkingManager ! CheckInResource(theStall.id, None)
            }
            theVehicle.unsetParkingStall()
          case None =>
        }
        val triggerToSchedule: Vector[ScheduleTrigger] = data.passengerSchedule
          .schedule(newLeg)
          .riders
          .map { personVehicle =>
            ScheduleTrigger(
              NotifyLegStartTrigger(tick, newLeg, data.currentVehicle.head),
              beamServices.personRefs(personVehicle.personId)
            )
          }
          .toVector
        eventsManager.processEvent(
          new VehicleEntersTrafficEvent(
            tick,
            Id.createPersonId(id),
            null,
            data.currentVehicle.head,
            "car",
            1.0
          )
        )
        // Produce link events for this trip (the same ones as in PathTraversalEvent).
        // TODO: They don't contain correct timestamps yet, but they all happen at the end of the trip!!
        // So far, we only throw them for ExperiencedPlans, which don't need timestamps.
        val beamLeg = data.passengerSchedule.schedule
          .drop(data.currentLegPassengerScheduleIndex)
          .head
          ._1
        RoutingModel
          .traverseStreetLeg_opt(beamLeg, data.currentVehicle.head)
          .foreach(eventsManager.processEvent)
        val endTime = tick + beamLeg.duration
        goto(Driving) using LiterallyDrivingData(data, endTime)
          .asInstanceOf[T] replying CompletionNotice(
          triggerId,
          triggerToSchedule ++ Vector(ScheduleTrigger(EndLegTrigger(endTime), self))
        )
      }
    case ev@Event(Interrupt(_, _), _) =>
      log.debug("state(DrivesVehicle.WaitingToDrive): {}", ev)
      stash()
      stay

    case ev@Event(
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
    case ev@Event(Resume(), _) =>
      log.debug("state(DrivesVehicle.WaitingToDriveInterrupted): {}", ev)
      goto(WaitingToDrive)

    case ev@Event(TriggerWithId(StartLegTrigger(_, _), _), _) =>
      log.debug("state(DrivesVehicle.WaitingToDriveInterrupted): {}", ev)
      stash()
      stay

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

  case class PassengerScheduleEmptyMessage(lastVisited: SpaceTime)

}
