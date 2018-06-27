package beam.agentsim.agents.modalBehaviors

import akka.actor.FSM.Failure
import akka.actor.Stash
import beam.agentsim.Resource.NotifyResourceIdle
import beam.agentsim.agents.BeamAgent
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.agents.modalBehaviors.DrivesVehicle._
import beam.agentsim.agents.rideHail.RideHailingAgent._
import beam.agentsim.agents.vehicles.AccessErrorCodes.VehicleFullError
import beam.agentsim.agents.parking.ChoosesParking.{ChoosesParkingData, ChoosingParkingSpot, ReleasingParkingSpot}
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

  case class StopDriving()

}

trait DrivesVehicle[T <: DrivingData] extends BeamAgent[T] with HasServices with Stash {

  protected val transportNetwork: TransportNetwork

  case class PassengerScheduleEmptyMessage(lastVisited: SpaceTime)

  when(Driving) {
    case Event(TriggerWithId(EndLegTrigger(tick), triggerId), LiterallyDrivingData(data, legEndingAt)) if tick == legEndingAt =>
      data.currentVehicle.headOption match {
        case Some(currentVehicleUnderControl) =>
          // If no manager is set, we ignore
          data.passengerSchedule.schedule.keys.drop(data.currentLegPassengerScheduleIndex).headOption match {
            case Some(currentLeg) =>
              beamServices.vehicles(currentVehicleUnderControl).manager.foreach( _ ! NotifyResourceIdle(currentVehicleUnderControl,beamServices.geo.wgs2Utm(currentLeg.travelPath.endPoint)))
              data.passengerSchedule.schedule(currentLeg).riders.foreach { pv =>
                beamServices.personRefs.get(pv.personId).foreach { personRef =>
                  logDebug(s"Scheduling NotifyLegEndTrigger for Person $personRef")
                  scheduler ! ScheduleTrigger(NotifyLegEndTrigger(tick, currentLeg), personRef)
                }
              }
              eventsManager.processEvent(new VehicleLeavesTrafficEvent(tick, id.asInstanceOf[Id[Person]], null, data.currentVehicle.head, "car", 0.0))
              eventsManager.processEvent(new PathTraversalEvent(tick, currentVehicleUnderControl,
                beamServices.vehicles(currentVehicleUnderControl).getType,
                data.passengerSchedule.schedule(currentLeg).riders.size, currentLeg))
            case None =>
              log.error("Current Leg is not available.")
          }
        case None =>
          log.error("Current Vehicle is not available.")
      }

      if (data.currentLegPassengerScheduleIndex + 1 < data.passengerSchedule.schedule.size) {
        if(data.hasParkingBehaviors){
          holdTickAndTriggerId(tick, triggerId)
          goto(ReadyToChooseParking) using data.withCurrentLegPassengerScheduleIndex(data.currentLegPassengerScheduleIndex + 1).asInstanceOf[T]
        }else {
          val nextLeg = data.passengerSchedule.schedule.keys.drop(data.currentLegPassengerScheduleIndex + 1).head
          goto(WaitingToDrive) using data.withCurrentLegPassengerScheduleIndex(data.currentLegPassengerScheduleIndex + 1).asInstanceOf[T] replying
            CompletionNotice(triggerId, Vector(ScheduleTrigger(StartLegTrigger(nextLeg.startTime, nextLeg), self)))
        }
      } else {
        holdTickAndTriggerId(tick, triggerId)
        self ! PassengerScheduleEmptyMessage(beamServices.geo.wgs2Utm(data.passengerSchedule.schedule.drop(data.currentLegPassengerScheduleIndex).head._1.travelPath.endPoint))
        goto(PassengerScheduleEmpty) using data.withCurrentLegPassengerScheduleIndex(data.currentLegPassengerScheduleIndex + 1).asInstanceOf[T]
      }

    case Event(TriggerWithId(EndLegTrigger(tick), triggerId), data) =>
      stay

    case Event(Interrupt(), data) =>
      goto(DrivingInterrupted) replying InterruptedAt(data.passengerSchedule, data.currentLegPassengerScheduleIndex)

  }

  when(DrivingInterrupted) {
    case Event(StopDriving(), LiterallyDrivingData(data, legEndingAt)) =>
      data.passengerSchedule.schedule.keys.drop(data.currentLegPassengerScheduleIndex).headOption match {
        case Some(currentLeg) =>
          assert(data.passengerSchedule.schedule(currentLeg).riders.isEmpty)
          data.currentVehicle.headOption match {
            case Some(currentVehicleUnderControl) =>
              // If no manager is set, we ignore
              beamServices.vehicles (currentVehicleUnderControl).manager.foreach (_ ! NotifyResourceIdle (currentVehicleUnderControl, beamServices.geo.wgs2Utm (currentLeg.travelPath.endPoint) ) )
              eventsManager.processEvent (new PathTraversalEvent (legEndingAt, currentVehicleUnderControl,
                beamServices.vehicles (currentVehicleUnderControl).getType,
                data.passengerSchedule.schedule (currentLeg).riders.size, currentLeg) )
            case None =>
              log.error("Current Vehicle is not available.")
          }
        case None =>
          log.error("Current Leg is not available.")
      }
      self ! PassengerScheduleEmptyMessage(beamServices.geo.wgs2Utm(data.passengerSchedule.schedule.drop(data.currentLegPassengerScheduleIndex).head._1.travelPath.endPoint))
      goto(PassengerScheduleEmptyInterrupted) using data.withCurrentLegPassengerScheduleIndex(data.currentLegPassengerScheduleIndex + 1).asInstanceOf[T]
    case Event(Resume(), _) =>
      goto(Driving)
    case Event(TriggerWithId(EndLegTrigger(_), _), _) =>
      stash()
      stay
  }

  when(WaitingToDrive) {
    case Event(TriggerWithId(StartLegTrigger(tick, newLeg), triggerId), data) =>
      data.passengerSchedule.schedule(newLeg).riders.foreach { personVehicle =>
        scheduler ! ScheduleTrigger(NotifyLegStartTrigger(tick, newLeg), beamServices.personRefs(personVehicle.personId))
      }
      eventsManager.processEvent(new VehicleEntersTrafficEvent(tick, Id.createPersonId(id), null, data.currentVehicle.head, "car", 1.0))
      // Produce link events for this trip (the same ones as in PathTraversalEvent).
      // TODO: They don't contain correct timestamps yet, but they all happen at the end of the trip!!
      // So far, we only throw them for ExperiencedPlans, which don't need timestamps.
      RoutingModel.traverseStreetLeg(data.passengerSchedule.schedule.drop(data.currentLegPassengerScheduleIndex).head._1, data.currentVehicle.head, (_,_) => 0L)
        .foreach(eventsManager.processEvent)

       val endTime = tick + data.passengerSchedule.schedule.drop(data.currentLegPassengerScheduleIndex).head._1.duration
      goto(Driving) using LiterallyDrivingData(data, endTime).asInstanceOf[T] replying CompletionNotice(triggerId, Vector(ScheduleTrigger(EndLegTrigger(endTime), self)))
  }

  when(WaitingToDriveInterrupted) {
    case Event(Resume(), _) =>
      goto(WaitingToDrive)
  }

  val drivingBehavior: StateFunction = {
    case Event(req: ReservationRequest, data) if !hasRoomFor(data.passengerSchedule, req, beamServices.vehicles(data.currentVehicle.head)) =>
      stay() replying ReservationResponse(req.requestId, Left(VehicleFullError))

    case Event(req: ReservationRequest, data) =>
      val legs = data.passengerSchedule.schedule.from(req.departFrom).to(req.arriveAt).keys.toSeq
      val legsInThePast = data.passengerSchedule.schedule.take(data.currentLegPassengerScheduleIndex).from(req.departFrom).to(req.arriveAt).keys.toSeq
      if (legsInThePast.nonEmpty) log.debug("Legs in the past: {}", legsInThePast)
      legsInThePast.foreach(leg => {
        scheduler ! ScheduleTrigger(NotifyLegStartTrigger(leg.startTime, leg), sender())
        scheduler ! ScheduleTrigger(NotifyLegEndTrigger(leg.endTime, leg), sender())
      })

      data.passengerSchedule.schedule.keys.drop(data.currentLegPassengerScheduleIndex).headOption match {
        case Some(currentLeg) =>
          if (stateName == Driving && legs.contains(currentLeg)) {
            scheduler ! ScheduleTrigger(NotifyLegStartTrigger(currentLeg.startTime, currentLeg), sender())
          }
        case None =>
          log.warning("Driver did not find a leg at currentLegPassengerScheduleIndex.")
      }

      stay() using data.withPassengerSchedule(data.passengerSchedule.addPassenger(req.passengerVehiclePersonId, legs)).asInstanceOf[T] replying ReservationResponse(req.requestId, Right(ReserveConfirmInfo(req.departFrom, req.arriveAt, req.passengerVehiclePersonId)))

    case Event(RemovePassengerFromTrip(id), data) =>
      stay() using data.withPassengerSchedule(PassengerSchedule(data.passengerSchedule.schedule ++ data.passengerSchedule.schedule.collect {
        case (leg, manifest) =>
          (leg, manifest.copy(riders = manifest.riders - id, alighters = manifest.alighters - id.vehicleId, boarders = manifest.boarders - id.vehicleId))
      })).asInstanceOf[T]
  }

  private def hasRoomFor(passengerSchedule: PassengerSchedule, req: ReservationRequest, vehicle: BeamVehicle) = {
    val vehicleCap = vehicle.getType.getCapacity
    val fullCap = vehicleCap.getSeats + vehicleCap.getStandingRoom
    passengerSchedule.schedule.from(req.departFrom).to(req.arriveAt).forall { entry =>
      entry._2.riders.size < fullCap
    }
  }

}
