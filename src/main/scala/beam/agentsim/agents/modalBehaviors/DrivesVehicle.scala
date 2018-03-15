package beam.agentsim.agents.modalBehaviors

import akka.actor.FSM.Failure
import beam.agentsim.Resource.NotifyResourceIdle
import beam.agentsim.agents.BeamAgent
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.agents.modalBehaviors.DrivesVehicle._
import beam.agentsim.agents.vehicles.AccessErrorCodes.{DriverHasEmptyPassengerScheduleError, VehicleFullError, VehicleGoneError}
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

  var lastVisited:  SpaceTime = SpaceTime.zero

  def passengerScheduleEmpty(tick: Double, triggerId: Long): State

  when(Driving) {
    case Event(TriggerWithId(EndLegTrigger(tick), triggerId), data) =>
      lastVisited = beamServices.geo.wgs2Utm(data.passengerSchedule.schedule.firstKey.travelPath.endPoint)
      val currentVehicleUnderControl = data.currentVehicle.head
      // If no manager is set, we ignore
      beamServices.vehicles(currentVehicleUnderControl).manager.foreach( _ ! NotifyResourceIdle(currentVehicleUnderControl,beamServices.geo.wgs2Utm(data.passengerSchedule.schedule.firstKey.travelPath.endPoint)))
      data.passengerSchedule.schedule.get(data.passengerSchedule.schedule.firstKey) match {
        case Some(manifest) =>
          manifest.riders.foreach { pv =>
            beamServices.personRefs.get(pv.personId).foreach { personRef =>
              logDebug(s"Scheduling NotifyLegEndTrigger for Person $personRef")
              scheduler ! ScheduleTrigger(NotifyLegEndTrigger(tick, data.passengerSchedule.schedule.firstKey), personRef)
            }
          }
          eventsManager.processEvent(new PathTraversalEvent(tick, currentVehicleUnderControl,
            beamServices.vehicles(currentVehicleUnderControl).getType,
            data.passengerSchedule.curTotalNumPassengers(data.passengerSchedule.schedule.firstKey), data.passengerSchedule.schedule.firstKey))

          data.passengerSchedule.schedule.remove(data.passengerSchedule.schedule.firstKey)

          if (data.passengerSchedule.schedule.nonEmpty) {
            val nextLeg = data.passengerSchedule.schedule.firstKey
            goto(WaitingToDrive) replying CompletionNotice(triggerId, Vector(ScheduleTrigger(StartLegTrigger(nextLeg.startTime, nextLeg), self)))
          } else {
            passengerScheduleEmpty(tick, triggerId)
          }
        case None =>
          throw new RuntimeException(s"Driver $id did not find a manifest for BeamLeg ${data.passengerSchedule.schedule.firstKey}")
      }
  }

  when(WaitingToDrive) {
    case Event(TriggerWithId(StartLegTrigger(tick, newLeg), triggerId), data) =>
      data.passengerSchedule.schedule.get(newLeg) match {
        case Some(manifest) =>
          manifest.riders.foreach { personVehicle =>
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
        case None =>
          stop(Failure(s"Driver $id did not find a manifest for BeamLeg $newLeg"))
      }
  }

  val drivingBehavior: StateFunction = {
    case Event(ModifyPassengerSchedule(updatedPassengerSchedule, _), data) if isNotCompatible(data.passengerSchedule, updatedPassengerSchedule) =>
      stop(Failure("Invalid attempt to ModifyPassengerSchedule, Spacetime of existing schedule incompatible with new"))

    case Event(ModifyPassengerSchedule(updatedPassengerSchedule, requestId), data) =>
      data.passengerSchedule.addLegs(updatedPassengerSchedule.schedule.keys.toSeq)
      updatedPassengerSchedule.schedule.foreach { legAndManifest =>
        legAndManifest._2.riders.foreach { rider =>
          data.passengerSchedule.addPassenger(rider, Seq(legAndManifest._1))
        }
      }
      stay() replying ModifyPassengerScheduleAck(requestId)

    case Event(req: ReservationRequest, data) if data.passengerSchedule.isEmpty =>
      log.warning(s"$id received ReservationRequestWithVehicle but passengerSchedule is empty")
      stay() replying ReservationResponse(req.requestId, Left(DriverHasEmptyPassengerScheduleError))

    case Event(req: ReservationRequest, data) if req.departFrom.startTime <= data.passengerSchedule.schedule.head._1.startTime =>
      stay() replying ReservationResponse(req.requestId, Left(VehicleGoneError))

    case Event(req: ReservationRequest, data) if !hasRoomFor(data.passengerSchedule, req, beamServices.vehicles(data.currentVehicle.head)) =>
      stay() replying ReservationResponse(req.requestId, Left(VehicleFullError))

    case Event(req: ReservationRequest, data) =>
      val legs = data.passengerSchedule.schedule.from(req.departFrom).to(req.arriveAt).keys.toSeq
      data.passengerSchedule.addPassenger(req.passengerVehiclePersonId, legs)
      stay() replying ReservationResponse(req.requestId, Right(ReserveConfirmInfo(req.departFrom, req.arriveAt, req.passengerVehiclePersonId)))

    case Event(RemovePassengerFromTrip(id), data) =>
      data.passengerSchedule.removePassenger(id)
      stay()
  }

  private def isNotCompatible(originalPassengerSchedule: PassengerSchedule, updatedPassengerSchedule: PassengerSchedule): Boolean = {
    if (!originalPassengerSchedule.isEmpty) {
      val endSpaceTime = originalPassengerSchedule.terminalSpacetime()
      if (updatedPassengerSchedule.initialSpacetime.time < endSpaceTime.time ||
        beamServices.geo.distInMeters(updatedPassengerSchedule.initialSpacetime.loc, endSpaceTime.loc) >
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
