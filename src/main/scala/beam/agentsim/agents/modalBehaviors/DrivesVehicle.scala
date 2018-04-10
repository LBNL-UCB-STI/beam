package beam.agentsim.agents.modalBehaviors

import akka.actor.FSM.Failure
import beam.agentsim.Resource.NotifyResourceIdle
import beam.agentsim.agents.BeamAgent
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.agents.modalBehaviors.DrivesVehicle._
import beam.agentsim.agents.vehicles.AccessErrorCodes.{VehicleFullError, VehicleGoneError}
import beam.agentsim.agents.vehicles.VehicleProtocol._
import beam.agentsim.agents.vehicles._
import beam.agentsim.events.{PathTraversalEvent, SpaceTime}
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.{Trigger, TriggerWithId}
import beam.router.RoutingModel
import beam.router.RoutingModel.BeamLeg
import beam.sim.HasServices
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.{VehicleEntersTrafficEvent, VehicleLeavesTrafficEvent}
import org.matsim.api.core.v01.population.Person

/**
  * @author dserdiuk on 7/29/17.
  */
object DrivesVehicle {

  case class StartLegTrigger(tick: Double, beamLeg: BeamLeg) extends Trigger

  case class EndLegTrigger(tick: Double) extends Trigger

  case class NotifyLegEndTrigger(tick: Double, beamLeg: BeamLeg) extends Trigger

  case class NotifyLegStartTrigger(tick: Double, beamLeg: BeamLeg) extends Trigger

}

trait DrivesVehicle[T <: DrivingData] extends BeamAgent[T] with HasServices {

  protected val transportNetwork: TransportNetwork

  case class PassengerScheduleEmptyMessage(lastVisited: SpaceTime)

  when(Driving) {
    // When I already started driving on the schedule.head leg, I cannot take reservations for it anymore
    case Event(req: ReservationRequest, data) if req.departFrom.startTime == data.passengerSchedule.schedule.head._1.startTime =>
      log.debug("Vehicle gone. Request: {} Schedule: {}", req, data.passengerSchedule.schedule)
      stay() replying ReservationResponse(req.requestId, Left(VehicleGoneError))

    case Event(TriggerWithId(EndLegTrigger(tick), triggerId), data) =>
      val currentVehicleUnderControl = data.currentVehicle.head
      // If no manager is set, we ignore
      beamServices.vehicles(currentVehicleUnderControl).manager.foreach( _ ! NotifyResourceIdle(currentVehicleUnderControl,beamServices.geo.wgs2Utm(data.passengerSchedule.schedule.firstKey.travelPath.endPoint)))
      data.passengerSchedule.schedule(data.passengerSchedule.schedule.firstKey).riders.foreach { pv =>
        beamServices.personRefs.get(pv.personId).foreach { personRef =>
          logDebug(s"Scheduling NotifyLegEndTrigger for Person $personRef")
          scheduler ! ScheduleTrigger(NotifyLegEndTrigger(tick, data.passengerSchedule.schedule.firstKey), personRef)
        }
      }
      eventsManager.processEvent(new PathTraversalEvent(tick, currentVehicleUnderControl,
        beamServices.vehicles(currentVehicleUnderControl).getType,
        data.passengerSchedule.schedule(data.passengerSchedule.schedule.firstKey).riders.size, data.passengerSchedule.schedule.firstKey))

      val newSchedule = PassengerSchedule(data.passengerSchedule.schedule - data.passengerSchedule.schedule.firstKey)

      if (newSchedule.schedule.nonEmpty) {
        val nextLeg = newSchedule.schedule.firstKey
        goto(WaitingToDrive) using data.withPassengerSchedule(newSchedule).asInstanceOf[T] replying CompletionNotice(triggerId, Vector(ScheduleTrigger(StartLegTrigger(nextLeg.startTime, nextLeg), self)))
      } else {
        holdTickAndTriggerId(tick, triggerId)
        self ! PassengerScheduleEmptyMessage(beamServices.geo.wgs2Utm(data.passengerSchedule.schedule.firstKey.travelPath.endPoint))
        goto(PassengerScheduleEmpty) using data.withPassengerSchedule(newSchedule).asInstanceOf[T]
      }
  }

  when(WaitingToDrive) {
    case Event(TriggerWithId(StartLegTrigger(tick, newLeg), triggerId), data) =>
      data.passengerSchedule.schedule(newLeg).riders.foreach { personVehicle =>
        logDebug(s"Scheduling NotifyLegStartTrigger for Person ${personVehicle.personId}")
        scheduler ! ScheduleTrigger(NotifyLegStartTrigger(tick, newLeg), beamServices.personRefs(personVehicle.personId))
      }
      eventsManager.processEvent(new VehicleEntersTrafficEvent(tick, Id.createPersonId(id), null, data.currentVehicle.head, "car", 1.0))
      // Produce link events for this trip (the same ones as in PathTraversalEvent).
      // TODO: They don't contain correct timestamps yet, but they all happen at the end of the trip!!
      // So far, we only throw them for ExperiencedPlans, which don't need timestamps.
      RoutingModel.traverseStreetLeg(data.passengerSchedule.schedule.firstKey, data.currentVehicle.head, (_,_) => 0L)
        .foreach(eventsManager.processEvent)
      val endTime = tick + data.passengerSchedule.schedule.firstKey.duration
      eventsManager.processEvent(new VehicleLeavesTrafficEvent(endTime, id.asInstanceOf[Id[Person]], null, data.currentVehicle.head, "car", 0.0))
      goto(Driving) replying CompletionNotice(triggerId, Vector(ScheduleTrigger(EndLegTrigger(endTime), self)))
  }

  val drivingBehavior: StateFunction = {
    case Event(ModifyPassengerSchedule(updatedPassengerSchedule, _), data) if isNotCompatible(data.passengerSchedule, updatedPassengerSchedule) =>
      stop(Failure("Invalid attempt to ModifyPassengerSchedule, Spacetime of existing schedule incompatible with new"))

    case Event(ModifyPassengerSchedule(updatedPassengerSchedule, requestId), data) =>
      var newPassengerSchedule = data.passengerSchedule.addLegs(updatedPassengerSchedule.schedule.keys.toSeq)
      updatedPassengerSchedule.schedule.foreach { legAndManifest =>
        legAndManifest._2.riders.foreach { rider =>
          newPassengerSchedule = newPassengerSchedule.addPassenger(rider, Seq(legAndManifest._1))
        }
      }
      stay() using data.withPassengerSchedule(newPassengerSchedule).asInstanceOf[T] replying ModifyPassengerScheduleAck(requestId)

    case Event(req: ReservationRequest, data) if data.passengerSchedule.schedule.isEmpty =>
      log.debug("Vehicle gone. Request: {} Schedule: {}", req, data.passengerSchedule.schedule)
      stay() replying ReservationResponse(req.requestId, Left(VehicleGoneError))

    case Event(req: ReservationRequest, data) if req.departFrom.startTime < data.passengerSchedule.schedule.head._1.startTime =>
      log.debug("Vehicle gone. Request: {} Schedule: {}", req, data.passengerSchedule.schedule)
      stay() replying ReservationResponse(req.requestId, Left(VehicleGoneError))

    case Event(req: ReservationRequest, data) if !hasRoomFor(data.passengerSchedule, req, beamServices.vehicles(data.currentVehicle.head)) =>
      stay() replying ReservationResponse(req.requestId, Left(VehicleFullError))

    case Event(req: ReservationRequest, data) =>
      val legs = data.passengerSchedule.schedule.from(req.departFrom).to(req.arriveAt).keys.toSeq
      stay() using data.withPassengerSchedule(data.passengerSchedule.addPassenger(req.passengerVehiclePersonId, legs)).asInstanceOf[T] replying ReservationResponse(req.requestId, Right(ReserveConfirmInfo(req.departFrom, req.arriveAt, req.passengerVehiclePersonId)))

    case Event(RemovePassengerFromTrip(id), data) =>
      stay() using data.withPassengerSchedule(PassengerSchedule(data.passengerSchedule.schedule ++ data.passengerSchedule.schedule.collect {
        case (leg, manifest) =>
          (leg, manifest.copy(riders = manifest.riders - id, alighters = manifest.alighters - id.vehicleId, boarders = manifest.boarders - id.vehicleId))
      })).asInstanceOf[T]
  }

  private def isNotCompatible(originalPassengerSchedule: PassengerSchedule, updatedPassengerSchedule: PassengerSchedule): Boolean = {
    if (originalPassengerSchedule.schedule.nonEmpty) {
      val endSpaceTime = originalPassengerSchedule.schedule.lastKey.travelPath.endPoint
      ()
      if (updatedPassengerSchedule.schedule.firstKey.travelPath.startPoint.time < endSpaceTime.time ||
        beamServices.geo.distInMeters(updatedPassengerSchedule.schedule.firstKey.travelPath.startPoint.loc, endSpaceTime.loc) >
          beamServices.beamConfig.beam.agentsim.thresholdForWalkingInMeters
      ) {
        return true
      }
    }
    false
  }

  private def hasRoomFor(passengerSchedule: PassengerSchedule, req: ReservationRequest, vehicle: BeamVehicle) = {
    val vehicleCap = vehicle.getType.getCapacity
    val fullCap = vehicleCap.getSeats + vehicleCap.getStandingRoom
    passengerSchedule.schedule.from(req.departFrom).to(req.arriveAt).forall { entry =>
      entry._2.riders.size < fullCap
    }
  }

}
