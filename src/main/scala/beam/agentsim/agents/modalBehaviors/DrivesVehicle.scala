package beam.agentsim.agents.modalBehaviors

import beam.agentsim.Resource.NotifyResourceIdle
import beam.agentsim.agents.BeamAgent
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.agents.modalBehaviors.DrivesVehicle._
import beam.agentsim.agents.rideHail.RideHailingAgent._
import beam.agentsim.agents.vehicles.AccessErrorCodes.VehicleFullError
import beam.agentsim.agents.vehicles.VehicleProtocol._
import beam.agentsim.agents.vehicles._
import beam.agentsim.events.{PathTraversalEvent, SpaceTime}
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.{Trigger, TriggerWithId}
import beam.router.RoutingModel
import beam.router.RoutingModel.BeamLeg
import beam.sim.HasServices
import beam.utils.DebugLib
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.api.core.v01.events.{VehicleEntersTrafficEvent, VehicleLeavesTrafficEvent}
import org.matsim.api.core.v01.population.Person
import org.matsim.vehicles.Vehicle

/**
  * @author dserdiuk on 7/29/17.
  */
object DrivesVehicle {

  case class StartLegTrigger(tick: Double, beamLeg: BeamLeg) extends Trigger

  case class EndLegTrigger(tick: Double) extends Trigger

  case class NotifyLegEndTrigger(tick: Double, beamLeg: BeamLeg) extends Trigger

  case class NotifyLegStartTrigger(tick: Double, beamLeg: BeamLeg) extends Trigger

  case class StopDriving()

  case class AddFuel(fuelInJoules: Double)
  case object GetBeamVehicleFuelLevel
  case class BeamVehicleFuelLevelUpdate(id: Id[Vehicle], fuelLevel:Double)

}

trait DrivesVehicle[T <: DrivingData] extends BeamAgent[T] with HasServices {

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


              beamServices.vehicles(currentVehicleUnderControl).useFuel(currentLeg.travelPath.distanceInM)


              data.passengerSchedule.schedule(currentLeg).riders.foreach { pv =>
                beamServices.personRefs.get(pv.personId).foreach { personRef =>
                  logDebug(s"Scheduling NotifyLegEndTrigger for Person $personRef")
                  scheduler ! ScheduleTrigger(NotifyLegEndTrigger(tick, currentLeg), personRef)
                }
              }
              eventsManager.processEvent(new VehicleLeavesTrafficEvent(tick, id.asInstanceOf[Id[Person]], null, data.currentVehicle.head, "car", 0.0))
              eventsManager.processEvent(new PathTraversalEvent(tick, currentVehicleUnderControl,
                beamServices.vehicles(currentVehicleUnderControl).getType,
                data.passengerSchedule.schedule(currentLeg).riders.size, currentLeg,beamServices.vehicles(currentVehicleUnderControl).fuelLevel.getOrElse(-1.0)))
            case None =>
              log.error("Current Leg is not available.")
          }
        case None =>
          log.error("Current Vehicle is not available.")
      }

      if (data.currentLegPassengerScheduleIndex + 1 < data.passengerSchedule.schedule.size) {
        val nextLeg = data.passengerSchedule.schedule.keys.drop(data.currentLegPassengerScheduleIndex + 1).head
        goto(WaitingToDrive) using data.withCurrentLegPassengerScheduleIndex(data.currentLegPassengerScheduleIndex + 1).asInstanceOf[T] replying CompletionNotice(triggerId, Vector(ScheduleTrigger(StartLegTrigger(nextLeg.startTime, nextLeg), self)))
      } else {
        holdTickAndTriggerId(tick, triggerId)
        self ! PassengerScheduleEmptyMessage(beamServices.geo.wgs2Utm(data.passengerSchedule.schedule.drop(data.currentLegPassengerScheduleIndex).head._1.travelPath.endPoint))
        goto(PassengerScheduleEmpty) using data.withCurrentLegPassengerScheduleIndex(data.currentLegPassengerScheduleIndex + 1).asInstanceOf[T]
      }

    case Event(TriggerWithId(EndLegTrigger(tick), triggerId), data) =>
      val currentVehicleUnderControl=data.passengerSchedule.schedule.keys.drop(data.currentLegPassengerScheduleIndex).headOption
      log.debug("DrivesVehicle.IgnoreEndLegTrigger: vehicleId(" + id + "),tick(" + tick + "),triggerId(" + triggerId + "),data(" + data + ")")
      stay replying CompletionNotice(triggerId, Vector())

    case Event(Interrupt(interruptId,tick), data) =>
      val currentVehicleUnderControl = beamServices.vehicles(data.currentVehicle.head)
      goto(DrivingInterrupted) replying InterruptedAt(interruptId,data.passengerSchedule, data.currentLegPassengerScheduleIndex, currentVehicleUnderControl.id,tick)

  }

  when(DrivingInterrupted) {
    case Event(StopDriving(), LiterallyDrivingData(data, legEndingAt)) =>
      data.passengerSchedule.schedule.keys.drop(data.currentLegPassengerScheduleIndex).headOption match {
        case Some(currentLeg) =>

          if (!data.passengerSchedule.schedule(currentLeg).riders.isEmpty){
            log.debug("DrivingInterrupted.StopDriving with rider: " + data.currentVehicle.head)
          }
          assert(data.passengerSchedule.schedule(currentLeg).riders.isEmpty)
          data.currentVehicle.headOption match {
            case Some(currentVehicleUnderControl) =>
              // If no manager is set, we ignore
              beamServices.vehicles (currentVehicleUnderControl).manager.foreach (_ ! NotifyResourceIdle (currentVehicleUnderControl, beamServices.geo.wgs2Utm (currentLeg.travelPath.endPoint) ) )
              eventsManager.processEvent (new PathTraversalEvent (legEndingAt, currentVehicleUnderControl,
                beamServices.vehicles (currentVehicleUnderControl).getType,
                data.passengerSchedule.schedule (currentLeg).riders.size, currentLeg,beamServices.vehicles(currentVehicleUnderControl).fuelLevel.getOrElse(-1.0)) )
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
    case Event(Interrupt(_,_), _) =>
      stash()
      stay
  }

  when(WaitingToDriveInterrupted) {
    case Event(Resume(), _) =>
      goto(WaitingToDrive)

    case Event(TriggerWithId(StartLegTrigger(tick, newLeg), triggerId), data) =>
      stash()
      stay

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


    case Event(AddFuel(fuelInJoules),data) =>
      val currentVehicleUnderControl = beamServices.vehicles(data.currentVehicle.head)
      currentVehicleUnderControl.addFuel(fuelInJoules)
      stay()

    case Event(GetBeamVehicleFuelLevel,data) =>
      // val currentLeg = data.passengerSchedule.schedule.keys.drop(data.currentLegPassengerScheduleIndex).head
      // as fuel is updated only at end of leg, might not be fully accurate - if want to do more accurate, will need to update fuel during leg
      // also position is not accurate (TODO: interpolate?)
      val currentVehicleUnderControl = beamServices.vehicles(data.currentVehicle.head)

      val lastLocationVisited=SpaceTime(new Coord(0,0),0) // TODO: don't ask for this here - TNC should keep track of it?
      // val lastLocationVisited = currentLeg.travelPath.endPoint

      sender() !  BeamVehicleFuelLevelUpdate(currentVehicleUnderControl.id, currentVehicleUnderControl.fuelLevel.get)
      stay()
  }

  private def hasRoomFor(passengerSchedule: PassengerSchedule, req: ReservationRequest, vehicle: BeamVehicle) = {
    val vehicleCap = vehicle.getType.getCapacity
    val fullCap = vehicleCap.getSeats + vehicleCap.getStandingRoom
    passengerSchedule.schedule.from(req.departFrom).to(req.arriveAt).forall { entry =>
      entry._2.riders.size < fullCap
    }
  }

}
