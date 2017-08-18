package beam.agentsim.agents.modalBehaviors

import akka.actor.FSM
import beam.agentsim.agents.BeamAgent.{AnyState, BeamAgentData}
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.agents.modalBehaviors.DrivesVehicle.{EndLegTrigger, NotifyLegEnd, NotifyLegStart, StartLegTrigger}
import beam.agentsim.agents.vehicles.BeamVehicle.{AlightingConfirmation, BeamVehicleIdAndRef, BecomeDriverSuccess, BoardingConfirmation, UnbecomeDriver, UpdateTrajectory, VehicleFull}
import beam.agentsim.agents.vehicles.{PassengerSchedule, VehiclePersonId}
import beam.agentsim.agents.{BeamAgent, TriggerShortcuts}
import beam.agentsim.events.AgentsimEventsBus.MatsimEvent
import beam.agentsim.events.PathTraversalEvent
import beam.agentsim.events.resources.vehicle._
import beam.agentsim.scheduler.{Trigger, TriggerWithId}
import beam.router.RoutingModel.{BeamLeg, EmbodiedBeamLeg}
import beam.sim.HasServices
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

import scala.collection.immutable.HashSet



/**
  * @author dserdiuk on 7/29/17.
  */
object DrivesVehicle {
  case class StartLegTrigger(tick: Double, beamLeg: EmbodiedBeamLeg) extends Trigger
  case class EndLegTrigger(tick: Double, beamLeg: EmbodiedBeamLeg) extends Trigger
  case class NotifyLegEnd(tick: Double)
  case class NotifyLegStart(tick: Double)
}

trait DrivesVehicle[T <: BeamAgentData] extends  TriggerShortcuts with HasServices with BeamAgent[T]{

  //TODO: double check that mutability here is legit espeically with the schedules passed in
  protected var passengerSchedule: PassengerSchedule = PassengerSchedule()

  protected var _currentLeg: Option[EmbodiedBeamLeg] = None
  //TODO: send some message to set _currentVehicle
  protected var _currentVehicleUnderControl: Option[BeamVehicleIdAndRef] = None
  protected var _awaitingBoardConfirmation: Set[Id[Vehicle]] = HashSet[Id[Vehicle]]()
  protected var _awaitingAlightConfirmation: Set[Id[Vehicle]] = HashSet[Id[Vehicle]]()

  chainedWhen(Moving) {
    case Event(TriggerWithId(EndLegTrigger(tick, completedLeg), triggerId), agentInfo) =>
      //we have just completed a leg
      logDebug(s"Received EndLeg for beamVehicleId=${_currentVehicleUnderControl.get.id}, started Boarding/Alighting   ")
      passengerSchedule.schedule.get(completedLeg.beamLeg) match {
        case Some(manifest) =>
          holdTickAndTriggerId(tick, triggerId)
          if(manifest.alighters.isEmpty){
            processNextLegOrCompleteMission()
          }else {
            _awaitingAlightConfirmation ++= manifest.alighters
            manifest.riders.foreach(pv => beamServices.personRefs.get(pv.personId).foreach(_ ! NotifyLegEnd))
            stay()
          }
        case None =>
          logError(s"Driver ${id} did not find a manifest for BeamLeg ${_currentLeg}")
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
      holdTickAndTriggerId(tick,triggerId)
      passengerSchedule.schedule.get(newLeg.beamLeg) match {
        case Some(manifest) =>
          _currentLeg = Some(newLeg)
          _currentVehicleUnderControl.get.ref ! UpdateTrajectory(newLeg.beamLeg.travelPath.toTrajectory)
          if(manifest.boarders.isEmpty){
            releaseAndScheduleEndLeg()
          }else {
            _awaitingBoardConfirmation ++= manifest.boarders
            manifest.riders.foreach(pv => beamServices.personRefs(pv.personId) ! NotifyLegStart(tick))
            stay()
          }
        case None =>
          logError(s"Driver ${id} did not find a manifest for BeamLeg ${newLeg}")
          goto(BeamAgent.Error)
      }
    case Event(BoardingConfirmation(vehicleId), agentInfo) =>
      _awaitingBoardConfirmation -= vehicleId
      if (_awaitingBoardConfirmation.isEmpty) {
        releaseAndScheduleEndLeg()
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
  private def releaseAndScheduleEndLeg(): FSM.State[BeamAgent.BeamAgentState, BeamAgent.BeamAgentInfo[T]] = {
    val (theTick, theTriggerId) = releaseTickAndTriggerId()
    goto(Moving) replying completed(theTriggerId, schedule[EndLegTrigger](_currentLeg.get.beamLeg.endTime,self,_currentLeg.get))
  }
  private def processNextLegOrCompleteMission() = {
    val (theTick, theTriggerId) = releaseTickAndTriggerId()
    val shouldExitVehicle = _currentLeg.get.unbecomeDriverOnCompletion
    beamServices.agentSimEventsBus.publish(MatsimEvent(new PathTraversalEvent(_currentVehicleUnderControl.get.id,_currentLeg.get.beamLeg)))
    _currentLeg = None
    passengerSchedule.schedule.remove(passengerSchedule.schedule.firstKey)
    if(passengerSchedule.schedule.nonEmpty){
      goto(Waiting) replying completed(theTriggerId, schedule[StartLegTrigger](passengerSchedule.schedule.firstKey.startTime,self,passengerSchedule.schedule.firstKey))
    }else{
      if(shouldExitVehicle)_currentVehicleUnderControl.get.ref ! UnbecomeDriver(theTick, id)
      goto(Waiting) replying completed(theTriggerId, schedule[CompleteDrivingMissionTrigger](theTick,self))
    }
  }

  private def handleVehicleReservation(req: ReservationRequest, vehicleIdToReserve: Id[Vehicle]) = {
    val response = _currentLeg match {
      case Some(currentLeg) if req.departFrom.startTime < currentLeg.beamLeg.startTime =>
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
