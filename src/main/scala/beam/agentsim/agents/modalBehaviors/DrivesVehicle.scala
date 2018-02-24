package beam.agentsim.agents.modalBehaviors

import akka.actor.FSM.Failure
import beam.agentsim.Resource.NotifyResourceIdle
import beam.agentsim.agents.BeamAgent
import beam.agentsim.agents.BeamAgent.{AnyState, BeamAgentData}
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.agents.TriggerUtils.{completed, _}
import beam.agentsim.agents.modalBehaviors.DrivesVehicle._
import beam.agentsim.agents.vehicles.AccessErrorCodes.{DriverHasEmptyPassengerScheduleError, VehicleFullError, VehicleGoneError}
import beam.agentsim.agents.vehicles.VehicleProtocol._
import beam.agentsim.agents.vehicles._
import beam.agentsim.events.{PathTraversalEvent, SpaceTime}
import beam.agentsim.scheduler.{Trigger, TriggerWithId}
import beam.router.RoutingModel
import beam.router.RoutingModel.BeamLeg
import beam.sim.HasServices
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.{PersonEntersVehicleEvent, PersonLeavesVehicleEvent, VehicleEntersTrafficEvent, VehicleLeavesTrafficEvent}
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

}

trait DrivesVehicle[T <: BeamAgentData] extends BeamAgent[T] with HasServices {

  protected val transportNetwork: TransportNetwork

  protected var passengerSchedule: PassengerSchedule = PassengerSchedule()
  var lastVisited:  SpaceTime = SpaceTime.zero
  protected var _currentLeg: Option[BeamLeg] = None
  protected var _currentVehicleUnderControl: Option[BeamVehicle] = None

  def passengerScheduleEmpty(tick: Double, triggerId: Long): State

  chainedWhen(Moving) {
    case Event(TriggerWithId(EndLegTrigger(tick), triggerId), _) =>
      lastVisited = beamServices.geo.wgs2Utm(_currentLeg.get.travelPath.endPoint)
      _currentVehicleUnderControl match {
        case Some(veh) =>
          // If no manager is set, we ignore
          veh.manager.foreach( _ ! NotifyResourceIdle(veh.id,beamServices.geo.wgs2Utm(_currentLeg.get.travelPath.endPoint)))
        case None =>
          throw new RuntimeException(s"Driver $id just ended a leg ${_currentLeg.get} but had no vehicle under control")
      }
      passengerSchedule.schedule.get(_currentLeg.get) match {
        case Some(manifest) =>
          manifest.riders.foreach { pv =>
            beamServices.personRefs.get(pv.personId).foreach { personRef =>
              logDebug(s"Scheduling NotifyLegEndTrigger for Person $personRef")
              scheduler ! scheduleOne[NotifyLegEndTrigger](tick, personRef, _currentLeg.get)
            }
          }
          manifest.alighters.foreach { alighter =>
            _currentVehicleUnderControl.foreach(veh=>
              if(!veh.removePassenger(alighter)){
                log.error(s"Attempted to remove passenger ${alighter} but was not on board ${id}")
              })
          }
          eventsManager.processEvent(new PathTraversalEvent(tick, _currentVehicleUnderControl.get.id,
            _currentVehicleUnderControl.get.getType,
            passengerSchedule.curTotalNumPassengers(_currentLeg.get),
            _currentLeg.get))

          _currentLeg = None
          passengerSchedule.schedule.remove(passengerSchedule.schedule.firstKey)

          if (passengerSchedule.schedule.nonEmpty) {
            val nextLeg = passengerSchedule.schedule.firstKey
            goto(Waiting) replying completed(triggerId, schedule[StartLegTrigger](nextLeg.startTime, self, nextLeg))
          } else {
            passengerScheduleEmpty(tick, triggerId)
          }
        case None =>
          throw new RuntimeException(s"Driver $id did not find a manifest for BeamLeg ${_currentLeg}")
      }
  }

  chainedWhen(Waiting) {
    case Event(TriggerWithId(StartLegTrigger(tick, newLeg), triggerId), _) =>
      passengerSchedule.schedule.get(newLeg) match {
        case Some(manifest) =>
          _currentLeg = Some(newLeg)
          manifest.riders.foreach { personVehicle =>
            logDebug(s"Scheduling NotifyLegStartTrigger for Person ${personVehicle.personId}")
            scheduler ! scheduleOne[NotifyLegStartTrigger](tick, beamServices.personRefs
            (personVehicle.personId), newLeg)
          }
          manifest.boarders.foreach { boarder =>
            _currentVehicleUnderControl.foreach{veh=>
              if(!veh.addPassenger(boarder)){
                log.error(s"Attempted to add passenger ${boarder} but vehicle was full ${id}")
              }
            }
          }
          eventsManager.processEvent(new VehicleEntersTrafficEvent(tick, Id.createPersonId(id), null, _currentVehicleUnderControl.get.id, "car", 1.0))
          // Produce link events for this trip (the same ones as in PathTraversalEvent).
          // TODO: They don't contain correct timestamps yet, but they all happen at the end of the trip!!
          // So far, we only throw them for ExperiencedPlans, which don't need timestamps.
          RoutingModel.traverseStreetLeg(_currentLeg.get, _currentVehicleUnderControl.get.id, (_,_) => 0L)
            .foreach(eventsManager.processEvent)
          val endTime = tick + _currentLeg.get.duration
          eventsManager.processEvent(new VehicleLeavesTrafficEvent(endTime, id.asInstanceOf[Id[Person]], null, _currentVehicleUnderControl.get.id, "car", 0.0))
          goto(Moving) replying completed(triggerId, schedule[EndLegTrigger](endTime, self))
        case None =>
          stop(Failure(s"Driver $id did not find a manifest for BeamLeg $newLeg"))
      }

    case Event(TriggerWithId(EndLegTrigger(tick), triggerId), _) =>
      stop(Failure(s"Received EndLegTrigger while in state Waiting. passenger schedule $passengerSchedule"))
  }

  chainedWhen(AnyState) {
    case Event(ModifyPassengerSchedule(updatedPassengerSchedule, _), _) if isNotCompatible(updatedPassengerSchedule) =>
      stop(Failure("Invalid attempt to ModifyPassengerSchedule, Spacetime of existing schedule incompatible with new"))

    case Event(ModifyPassengerSchedule(updatedPassengerSchedule, requestId), _) =>
      passengerSchedule.addLegs(updatedPassengerSchedule.schedule.keys.toSeq)
      updatedPassengerSchedule.schedule.foreach { legAndManifest =>
        legAndManifest._2.riders.foreach { rider =>
          passengerSchedule.addPassenger(rider, Seq(legAndManifest._1))
        }
      }
      _currentLeg match {
        case None =>
          goto(Waiting) replying ModifyPassengerScheduleAck(requestId)
        case Some(_) =>
          stay() replying ModifyPassengerScheduleAck(requestId)
      }

    case Event(req: ReservationRequest, _) if passengerSchedule.isEmpty =>
      log.warning(s"$id received ReservationRequestWithVehicle but passengerSchedule is empty")
      stay() replying ReservationResponse(req.requestId, Left(DriverHasEmptyPassengerScheduleError))

    case Event(req: ReservationRequest, _) if req.departFrom.startTime < passengerSchedule.schedule.head._1.startTime =>
      stay() replying ReservationResponse(req.requestId, Left(VehicleGoneError))

    case Event(req: ReservationRequest, _) if !hasRoomFor(req) =>
      stay() replying ReservationResponse(req.requestId, Left(VehicleFullError))

    case Event(req: ReservationRequest, _) =>
      val legs = passengerSchedule.schedule.from(req.departFrom).to(req.arriveAt).keys.toSeq
      passengerSchedule.addPassenger(req.passengerVehiclePersonId, legs)
      stay() replying ReservationResponse(req.requestId, Right(ReserveConfirmInfo(req.departFrom, req.arriveAt, req.passengerVehiclePersonId)))

    case Event(RemovePassengerFromTrip(id),_)=>
      passengerSchedule.removePassenger(id)
      stay()

  }

  private def isNotCompatible(updatedPassengerSchedule: PassengerSchedule): Boolean = {
    if (!passengerSchedule.isEmpty) {
      val endSpaceTime = passengerSchedule.terminalSpacetime()
      if (updatedPassengerSchedule.initialSpacetime.time < endSpaceTime.time ||
        beamServices.geo.distInMeters(updatedPassengerSchedule.initialSpacetime.loc, endSpaceTime.loc) >
          beamServices.beamConfig.beam.agentsim.thresholdForWalkingInMeters
      ) {
        return true
      }
    }
    false
  }

  def hasRoomFor(req: ReservationRequest) = {
    val tripReservations = passengerSchedule.schedule.from(req.departFrom).to(req.arriveAt).toVector
    val vehicleCap = _currentVehicleUnderControl.get.getType.getCapacity
    val fullCap = vehicleCap.getSeats + vehicleCap.getStandingRoom
    val hasRoom = tripReservations.forall { entry =>
      entry._2.riders.size < fullCap
    }
    hasRoom
  }

  def setPassengerSchedule(newPassengerSchedule: PassengerSchedule) = {
    passengerSchedule = newPassengerSchedule
  }

  def modifyPassengerSchedule(updatedPassengerSchedule: PassengerSchedule)={
    var errorFlag = false
    if (!passengerSchedule.isEmpty) {
      val endSpaceTime = passengerSchedule.terminalSpacetime()
      if (updatedPassengerSchedule.initialSpacetime.time < endSpaceTime.time ||
        beamServices.geo.distInMeters(updatedPassengerSchedule.initialSpacetime.loc, endSpaceTime.loc) >
          beamServices.beamConfig.beam.agentsim.thresholdForWalkingInMeters
      ) {
        errorFlag = true
      }
    }
    if (errorFlag) {
      stop(Failure("Invalid attempt to ModifyPassengerSchedule, Spacetime of existing schedule incompatible with " +
        "new"))
    } else {
      passengerSchedule.addLegs(updatedPassengerSchedule.schedule.keys.toSeq)
      updatedPassengerSchedule.schedule.foreach { legAndManifest =>
        legAndManifest._2.riders.foreach { rider =>
          passengerSchedule.addPassenger(rider, Seq(legAndManifest._1))
        }
      }
    }
  }

  def becomeDriverOfVehicle(vehicleId: Id[Vehicle], tick: Double) = {
    val vehicle = beamServices.vehicles(vehicleId)
    vehicle.becomeDriver(self).fold(fa =>
      stop(Failure(s"BeamAgent $self attempted to become driver of vehicle $id " +
        s"but driver ${vehicle.driver.get} already assigned.")),
      fb => {
        _currentVehicleUnderControl = Some(vehicle)
        eventsManager.processEvent(new PersonEntersVehicleEvent(tick, Id.createPersonId(id), vehicleId))
      })
  }

  def resumeControlOfVehcile(vehicleId: Id[Vehicle]) = {
    _currentVehicleUnderControl = Some(beamServices.vehicles(vehicleId))
  }

  def unbecomeDriverOfVehicle(vehicleId: Id[Vehicle], tick: Double): Unit ={
    beamServices.vehicles(vehicleId).unsetDriver()
    eventsManager.processEvent(new PersonLeavesVehicleEvent(tick, Id.createPersonId(id), vehicleId))
  }

}
