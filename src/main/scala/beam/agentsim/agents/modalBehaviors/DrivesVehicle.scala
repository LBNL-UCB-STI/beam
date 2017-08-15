package beam.agentsim.agents.modalBehaviors

import beam.agentsim.agents.BeamAgent.{BeamAgentData, AnyState}
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.agents.modalBehaviors.DrivesVehicle.{EndLegTrigger, NotifyLegEnd, NotifyLegStart, StartLegTrigger}
import beam.agentsim.agents.vehicles.BeamVehicle.{AlightingConfirmation, BeamVehicleIdAndRef, BecomeDriverSuccess, BoardingConfirmation, UnbecomeDriver, UpdateTrajectory, VehicleFull}
import beam.agentsim.agents.vehicles.{PassengerSchedule, VehiclePersonId}
import beam.agentsim.agents.{BeamAgent, TriggerShortcuts}
import beam.agentsim.events.resources.vehicle._
import beam.agentsim.scheduler.{Trigger, TriggerWithId}
import beam.router.RoutingModel.BeamLeg
import beam.sim.HasServices
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

import scala.collection.immutable.HashSet



/**
  * @author dserdiuk on 7/29/17.
  */
object DrivesVehicle {
  case class StartLegTrigger(tick: Double, beamLeg: BeamLeg) extends Trigger
  case class EndLegTrigger(tick: Double, beamLeg: BeamLeg) extends Trigger
  case class NotifyLegEnd(tick: Double)
  case class NotifyLegStart(tick: Double)
}

trait DrivesVehicle[T <: BeamAgentData] extends  TriggerShortcuts with HasServices with BeamAgent[T]{

  //TODO: double check that mutability here is legit espeically with the schedules passed in
  protected var passengerSchedule: PassengerSchedule = PassengerSchedule()

  protected var _currentLeg: Option[BeamLeg] = None
  //TODO: send some message to set _currentVehicle
  protected var _currentVehicleUnderControl: Option[BeamVehicleIdAndRef] = None
  protected var _awaitingBoardConfirmation: Set[Id[Vehicle]] = HashSet[Id[Vehicle]]()
  protected var _awaitingAlightConfirmation: Set[Id[Vehicle]] = HashSet[Id[Vehicle]]()

  chainedWhen(Moving) {
    case Event(TriggerWithId(EndLegTrigger(tick, completedLeg), triggerId), agentInfo) =>
      //we have just completed a leg
      log.debug(s"Received EndLeg for beamVehicleId=${_currentVehicleUnderControl.get.id}, started Boarding/Alighting   ")
      _currentTriggerId = Some(triggerId)
      _currentTick = Some(tick)
      passengerSchedule.schedule.get(completedLeg) match {
        case Some(manifest) =>
          _awaitingAlightConfirmation ++= manifest.alighters
          manifest.riders.foreach(pv => beamServices.personRefs.get(pv.personId).foreach( _  ! NotifyLegEnd))
          stay()
        case None =>
          log.error(s"Driver ${id} did not find a manifest for BeamLeg ${_currentLeg}")
          goto(BeamAgent.Error)
      }
    case Event(AlightingConfirmation(vehicleId), agentInfo) =>
      _awaitingAlightConfirmation -= vehicleId
      if (_awaitingAlightConfirmation.isEmpty) {
        processNextLegOrCompleteMission()
      } else {
        stay()
      }
  }
  chainedWhen(Waiting) {
    case Event(BecomeDriverSuccess(newPassengerSchedule, assignedVehicle), info) =>
      _currentVehicleUnderControl = beamServices.vehicleRefs.get(assignedVehicle).map { vehicleRef =>
        BeamVehicleIdAndRef(assignedVehicle, vehicleRef)
      }
      newPassengerSchedule match {
        case Some(passSched) =>
          passengerSchedule = passSched
        case None =>
          passengerSchedule = PassengerSchedule()
      }
      stay()
    case Event(TriggerWithId(StartLegTrigger(tick, newLeg), triggerId), agentInfo) =>
      _currentTriggerId = Some(triggerId)
      _currentTick = Some(tick)
      passengerSchedule.schedule.get(newLeg) match {
        case Some(manifest) =>
          _awaitingBoardConfirmation ++= manifest.boarders
          manifest.riders.foreach(pv => beamServices.personRefs(pv.personId) ! NotifyLegStart)
          _currentVehicleUnderControl.foreach( _.ref ! UpdateTrajectory(newLeg.travelPath.toTrajectory) )
          stay()
        case None =>
          log.error(s"Driver ${id} did not find a manifest for BeamLeg ${_currentLeg}")
          goto(BeamAgent.Error)
      }
    case Event(BoardingConfirmation(vehicleId), agentInfo) =>
      _awaitingBoardConfirmation -= vehicleId
      if (_awaitingBoardConfirmation.isEmpty) {
        val theTriggerId = _currentTriggerId.get
        _currentTriggerId = None
        val theTick = _currentTick.get
        _currentTick = None
        goto(Moving) replying completed(theTriggerId, schedule[EndLegTrigger](_currentLeg.get.endTime,self))
      } else {
        stay()
      }
  }
  chainedWhen(AnyState){
    case Event(ReservationRequestWithVehicle(req, vehicleIdToReserve), _) =>
      require(passengerSchedule.schedule.nonEmpty, "Driver needs to init list of stops")
      val response = handleVehicleReservation(req, vehicleIdToReserve)
      beamServices.personRefs(req.requester) ! response
      stay()
  }

  private def processNextLegOrCompleteMission() = {
    val theTriggerId = _currentTriggerId.get
    _currentTriggerId = None
    val theTick = _currentTick.get
    _currentTick = None

    passengerSchedule.schedule.remove(passengerSchedule.schedule.firstKey)
    if(passengerSchedule.schedule.nonEmpty){
      _currentLeg = Some(passengerSchedule.schedule.firstKey)
      goto(Waiting) replying completed(theTriggerId, schedule[StartLegTrigger](_currentLeg.get.startTime,self))
    }else{
      _currentVehicleUnderControl.get.ref ! UnbecomeDriver(theTick, id)
      goto(Waiting) replying completed(theTriggerId, schedule[CompleteDrivingMissionTrigger](theTick,self))
    }
  }

  private def handleVehicleReservation(req: ReservationRequest, vehicleIdToReserve: Id[Vehicle]) = {
    val response = _currentLeg match {
      case Some(currentLeg) if req.departFrom.startTime < currentLeg.startTime =>
        ReservationResponse(req.requestId, Left(VehicleGone))
      case _ =>
        val tripReservations = passengerSchedule.schedule.from(req.departFrom).to(req.arriveAt)
        val vehicleCap = beamServices.vehicles(vehicleIdToReserve).getType.getCapacity
        val fullCap = vehicleCap.getSeats + vehicleCap.getStandingRoom
        val hasRoom = tripReservations.forall { entry =>
          entry._2.riders.size < fullCap
        }
        if (hasRoom) {
          val legs = tripReservations.keys.toList
          passengerSchedule.addPassenger(VehiclePersonId(req.passenger, req.requester), legs)
          ReservationResponse(req.requestId, Right(ReserveConfirmInfo(req.departFrom, req.arriveAt, req.passenger, vehicleIdToReserve)))
        } else {
          ReservationResponse(req.requestId, Left(VehicleFull(vehicleIdToReserve)))
        }
    }
    response
  }
}
