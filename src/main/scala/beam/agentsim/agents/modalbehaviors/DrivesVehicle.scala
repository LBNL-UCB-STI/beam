package beam.agentsim.agents.modalbehaviors

import beam.agentsim.Resource.NotifyResourceIdle
import beam.agentsim.agents.BeamAgent
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.agents.modalbehaviors.DrivesVehicle._
import beam.agentsim.agents.ridehail.RideHailAgent._
import beam.agentsim.agents.ridehail.RideHailUtils
import beam.agentsim.agents.vehicles.AccessErrorCodes.VehicleFullError
import beam.agentsim.agents.vehicles.VehicleProtocol._
import beam.agentsim.agents.vehicles._
import beam.agentsim.events.{PathTraversalEvent, SpaceTime}
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

  case class StartLegTrigger(tick: Double, beamLeg: BeamLeg) extends Trigger

  case class EndLegTrigger(tick: Double) extends Trigger

  case class NotifyLegEndTrigger(tick: Double, beamLeg: BeamLeg, vehicleId: Id[Vehicle])
      extends Trigger

  case class NotifyLegStartTrigger(tick: Double, beamLeg: BeamLeg, vehicleId: Id[Vehicle])
      extends Trigger

  case class StopDriving(tick: Double)

  case class AddFuel(fuelInJoules: Double)

  case object GetBeamVehicleFuelLevel

  case class BeamVehicleFuelLevelUpdate(id: Id[Vehicle], fuelLevel: Double)

}

trait DrivesVehicle[T <: DrivingData] extends BeamAgent[T] with HasServices {

  protected val transportNetwork: TransportNetwork

  case class PassengerScheduleEmptyMessage(lastVisited: SpaceTime)

  when(Driving) {
    case ev @ Event(
          TriggerWithId(EndLegTrigger(tick), triggerId),
          LiterallyDrivingData(data, legEndingAt)
        ) if tick == legEndingAt =>
      log.debug("state(DrivesVehicle.Driving): {}", ev)

      val isLastLeg = data.currentLegPassengerScheduleIndex + 1 == data.passengerSchedule.schedule.size
      data.currentVehicle.headOption match {
        case Some(currentVehicleUnderControl) =>
          // If no manager is set, we ignore
          data.passengerSchedule.schedule.keys
            .drop(data.currentLegPassengerScheduleIndex)
            .headOption match {
            case Some(currentLeg) =>
              if (isLastLeg)
                beamServices
                  .vehicles(currentVehicleUnderControl)
                  .manager
                  .foreach(
                    _ ! NotifyResourceIdle(
                      currentVehicleUnderControl,
                      beamServices.geo.wgs2Utm(currentLeg.travelPath.endPoint),
                      data.passengerSchedule
                    )
                  )
              beamServices
                .vehicles(currentVehicleUnderControl)
                .useFuel(currentLeg.travelPath.distanceInM)

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
                  beamServices.vehicles(currentVehicleUnderControl).getType,
                  data.passengerSchedule.schedule(currentLeg).riders.size,
                  currentLeg,
                  beamServices
                    .vehicles(currentVehicleUnderControl)
                    .fuelLevel
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
        val nextLeg =
          data.passengerSchedule.schedule.keys
            .drop(data.currentLegPassengerScheduleIndex + 1)
            .head
        goto(WaitingToDrive) using data
          .withCurrentLegPassengerScheduleIndex(data.currentLegPassengerScheduleIndex + 1)
          .asInstanceOf[T] replying CompletionNotice(
          triggerId,
          Vector(ScheduleTrigger(StartLegTrigger(nextLeg.startTime, nextLeg), self))
        )
      } else {
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
      val currentVehicleUnderControl =
        beamServices.vehicles(data.currentVehicle.head)
      goto(DrivingInterrupted) replying InterruptedAt(
        interruptId,
        data.passengerSchedule,
        data.currentLegPassengerScheduleIndex,
        currentVehicleUnderControl.id,
        tick
      )

  }

  when(DrivingInterrupted) {
    case ev @ Event(StopDriving(stopTick), LiterallyDrivingData(data, _)) =>
      log.debug("state(DrivesVehicle.DrivingInterrupted): {}", ev)
      val isLastLeg = data.currentLegPassengerScheduleIndex + 1 == data.passengerSchedule.schedule.size
      data.passengerSchedule.schedule.keys
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
              // If no manager is set, we ignore

              // TODO: can we update the current leg based on the stop time?
              // as this kind of stop happens seldomly, we might try to send a query to any entity which has access to the network, e.g. router or RideHailManager?

              //val a = beamServices.geo.getNearestR5Edge(transportNetwork.streetLayer,currentLeg.travelPath.endPoint.loc,10000)

              val updatedBeamLeg =
                RideHailUtils.getUpdatedBeamLegAfterStopDriving(
                  currentLeg,
                  stopTick,
                  transportNetwork
                )

              if (isLastLeg)
                beamServices
                  .vehicles(currentVehicleUnderControl)
                  .manager
                  .foreach(
                    _ ! NotifyResourceIdle(
                      currentVehicleUnderControl,
                      beamServices.geo.wgs2Utm(updatedBeamLeg.travelPath.endPoint),
                      data.passengerSchedule
                    )
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
                  beamServices.vehicles(currentVehicleUnderControl).getType,
                  data.passengerSchedule.schedule(currentLeg).riders.size,
                  updatedBeamLeg,
                  beamServices
                    .vehicles(currentVehicleUnderControl)
                    .fuelLevel
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
      log.debug("state(DrivesVehicle.WaitingToDrive): {}", ev)
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
      RoutingModel
        .traverseStreetLeg(
          data.passengerSchedule.schedule
            .drop(data.currentLegPassengerScheduleIndex)
            .head
            ._1,
          data.currentVehicle.head,
          (_, _) => 0L
        )
        .foreach(eventsManager.processEvent)
      val endTime = tick + data.passengerSchedule.schedule
        .drop(data.currentLegPassengerScheduleIndex)
        .head
        ._1
        .duration
      goto(Driving) using LiterallyDrivingData(data, endTime)
        .asInstanceOf[T] replying CompletionNotice(
        triggerId,
        triggerToSchedule ++ Vector(ScheduleTrigger(EndLegTrigger(endTime), self))
      )
    case ev @ Event(Interrupt(_, _), _) =>
      log.debug("state(DrivesVehicle.WaitingToDrive): {}", ev)
      stash()
      stay
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
          beamServices.vehicles(data.currentVehicle.head)
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
        log.warning("Legs in the past: {} -- {}", legsInThePast, req)
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
      val triggersToSchedule2 = data.passengerSchedule.schedule.keys
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
                      alighters = manifest.alighters - id.vehicleId,
                      boarders = manifest.boarders - id.vehicleId
                    )
                  )
              }
          )
        )
        .asInstanceOf[T]

    case ev @ Event(AddFuel(fuelInJoules), data) =>
      log.debug("state(DrivesVehicle.drivingBehavior): {}", ev)
      val currentVehicleUnderControl =
        beamServices.vehicles(data.currentVehicle.head)
      currentVehicleUnderControl.addFuel(fuelInJoules)
      stay()

    case ev @ Event(GetBeamVehicleFuelLevel, data) =>
      log.debug("state(DrivesVehicle.drivingBehavior): {}", ev)
      // val currentLeg = data.passengerSchedule.schedule.keys.drop(data.currentLegPassengerScheduleIndex).head
      // as fuel is updated only at end of leg, might not be fully accurate - if want to do more accurate, will need to update fuel during leg
      // also position is not accurate (TODO: interpolate?)
      val currentVehicleUnderControl =
        beamServices.vehicles(data.currentVehicle.head)

      //      val lastLocationVisited = SpaceTime(new Coord(0, 0), 0) // TODO: don't ask for this here - TNC should keep track of it?
      // val lastLocationVisited = currentLeg.travelPath.endPoint

      sender() ! BeamVehicleFuelLevelUpdate(
        currentVehicleUnderControl.id,
        currentVehicleUnderControl.fuelLevel.get
      )
      stay()
  }

  private def hasRoomFor(
    passengerSchedule: PassengerSchedule,
    req: ReservationRequest,
    vehicle: BeamVehicle
  ) = {
    val vehicleCap = vehicle.getType.getCapacity
    val fullCap = vehicleCap.getSeats + vehicleCap.getStandingRoom
    passengerSchedule.schedule.from(req.departFrom).to(req.arriveAt).forall { entry =>
      entry._2.riders.size < fullCap
    }
  }

}
