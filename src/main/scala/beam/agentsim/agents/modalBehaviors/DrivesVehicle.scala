package beam.agentsim.agents.modalBehaviors

import akka.actor.FSM
import beam.agentsim.agents.BeamAgent.{AnyState, BeamAgentData}
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.agents.modalBehaviors.DrivesVehicle._
import beam.agentsim.agents.vehicles.BeamVehicle.{AlightingConfirmation, BeamVehicleIdAndRef, BecomeDriverSuccess, BecomeDriverSuccessAck, BoardingConfirmation, UpdateTrajectory, VehicleFull}
import beam.agentsim.agents.vehicles.{BeamVehicle, PassengerSchedule, VehiclePersonId}
import beam.agentsim.agents.BeamAgent
import beam.agentsim.agents.TriggerUtils._
import beam.agentsim.events.AgentsimEventsBus.MatsimEvent
import beam.agentsim.events.PathTraversalEvent
import beam.agentsim.events.resources.vehicle._
import beam.agentsim.scheduler.{Trigger, TriggerWithId}
import beam.router.RoutingModel.{BeamLeg, EmbodiedBeamLeg}
import beam.sim.HasServices
import beam.sim.common.GeoUtils
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

import scala.collection.immutable.HashSet

/**
  * @author dserdiuk on 7/29/17.
  */
object DrivesVehicle {
  case class StartLegTrigger(tick: Double, beamLeg: BeamLeg) extends Trigger
  case class EndLegTrigger(tick: Double, beamLeg: BeamLeg) extends Trigger
  case class NotifyLegEndTrigger(tick: Double, beamLeg: BeamLeg) extends Trigger
  case class NotifyLegStartTrigger(tick: Double, beamLeg: BeamLeg) extends Trigger
}

trait DrivesVehicle[T <: BeamAgentData] extends BeamAgent[T] with HasServices {

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
      logDebug(s"Received EndLeg($tick, ${completedLeg.endTime}) for beamVehicleId=${_currentVehicleUnderControl.get.id}, started Boarding/Alighting   ")
      passengerSchedule.schedule.get(completedLeg) match {
        case Some(manifest) =>
          holdTickAndTriggerId(tick, triggerId)
          manifest.riders.foreach { pv =>
            beamServices.personRefs.get(pv.personId).foreach { personRef =>
              logDebug(s"Scheduling NotifyLegEndTrigger for Person ${personRef}")
              beamServices.schedulerRef ! scheduleOne[NotifyLegEndTrigger](tick, personRef,completedLeg)
            }
          }
          if(manifest.alighters.isEmpty){
            processNextLegOrCompleteMission()
          }else {
            logDebug(s" will wait for ${manifest.alighters.size} alighters: ${manifest.alighters}")
            _awaitingAlightConfirmation ++= manifest.alighters
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
    case Event(TriggerWithId(StartLegTrigger(tick, newLeg), triggerId), agentInfo) =>
      if(id.toString.equals("TransitDriverAgent-BA.gtfs:05R10")){
        val i =0
      }
      holdTickAndTriggerId(tick,triggerId)
      logDebug(s"Received StartLeg($tick, ${newLeg.startTime}) for beamVehicleId=${_currentVehicleUnderControl.get.id} ")

      passengerSchedule.schedule.get(newLeg) match {
        case Some(manifest) =>
          _currentLeg = Some(newLeg)
          _currentVehicleUnderControl.get.ref ! UpdateTrajectory(newLeg.travelPath.toTrajectory)
          manifest.riders.foreach{ personVehicle =>
            logDebug(s"Scheduling NotifyLegStartTrigger for Person ${personVehicle.personId}")
            beamServices.schedulerRef ! scheduleOne[NotifyLegStartTrigger](tick, beamServices.personRefs(personVehicle.personId), newLeg)
          }
          if(manifest.boarders.isEmpty){
            releaseAndScheduleEndLeg()
          }else {
            _awaitingBoardConfirmation ++= manifest.boarders
            stay()
          }
        case None =>
          logError(s"Driver ${id} did not find a manifest for BeamLeg ${newLeg}")
          goto(BeamAgent.Error) replying completed(triggerId)

      }
    case Event(BoardingConfirmation(vehicleId), agentInfo) =>
      _awaitingBoardConfirmation -= vehicleId
      if (_awaitingBoardConfirmation.isEmpty) {
        releaseAndScheduleEndLeg()
      } else {
        stay()
      }
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
      self ! BecomeDriverSuccessAck
      goto(Waiting)
  }
  chainedWhen(AnyState){
    // Problem, when this is received from PersonAgent, it is due to a NotifyEndLeg trigger which doesn't have an ack
    // So the schedule has moved ahead before this can schedule a new StartLegTrigger, so maybe Notify*Leg should be Triggers?
    case Event(ModifyPassengerSchedule(updatedPassengerSchedule,requestId), _) =>
      var errorFlag = false
      if(!passengerSchedule.isEmpty){
        val endSpaceTime = passengerSchedule.terminalSpacetime()
        if(updatedPassengerSchedule.initialSpacetime.time < endSpaceTime.time ||
          beamServices.geo.distInMeters(updatedPassengerSchedule.initialSpacetime.loc,endSpaceTime.loc) > beamServices.beamConfig.beam.agentsim.thresholdForWalkingInMeters
        ) {
          errorFlag = true
        }
      }
      if(errorFlag) {
        logError("Invalid attempt to ModifyPassengerSchedule, Spacetime of existing schedule incompatible with new")
        goto(BeamAgent.Error)
      }else{
        passengerSchedule.addLegs(updatedPassengerSchedule.schedule.keys.toSeq)
        updatedPassengerSchedule.schedule.foreach{ legAndManifest =>
          legAndManifest._2.riders.foreach { rider =>
            passengerSchedule.addPassenger(rider,Seq(legAndManifest._1))
          }
        }
        val resultingState = _currentLeg match {
          case None =>
            goto(Waiting) replying ModifyPassengerScheduleAck(requestId)
          case Some(beamLeg) =>
            stay() replying ModifyPassengerScheduleAck(requestId)
        }
        resultingState
      }
    case Event(ReservationRequestWithVehicle(req, vehicleIdToReserve), _) =>
      require(passengerSchedule.schedule.nonEmpty, "Driver needs to init list of stops")
      logDebug(s"Received Reservation(vehicle=$vehicleIdToReserve, boardingLeg=${req.departFrom.startTime}, alighting=${req.arriveAt.startTime}) ")

      val response = handleVehicleReservation(req, vehicleIdToReserve)
      beamServices.personRefs(req.passengerVehiclePersonId.personId) ! response
      stay()
  }

  private def releaseAndScheduleEndLeg(): FSM.State[BeamAgent.BeamAgentState, BeamAgent.BeamAgentInfo[T]] = {
    val (theTick, theTriggerId) = releaseTickAndTriggerId()
    beamServices.schedulerRef ! completed(theTriggerId, schedule[EndLegTrigger](_currentLeg.get.endTime,self,_currentLeg.get))
    goto(Moving)
  }

  private def processNextLegOrCompleteMission() = {
    val (theTick, theTriggerId) = releaseTickAndTriggerId()
    beamServices.agentSimEventsBus.publish(MatsimEvent(new PathTraversalEvent(theTick,_currentVehicleUnderControl.get.id,_currentLeg.get)))
    _currentLeg = None
    passengerSchedule.schedule.remove(passengerSchedule.schedule.firstKey)
    if(passengerSchedule.schedule.nonEmpty){
      val nextLeg = passengerSchedule.schedule.firstKey
      beamServices.schedulerRef ! completed(theTriggerId, schedule[StartLegTrigger](nextLeg.startTime,self,  nextLeg))
      goto(Waiting)
    }else{
      beamServices.schedulerRef ! completed(theTriggerId, schedule[PassengerScheduleEmptyTrigger](theTick, self))
      goto(Waiting)
    }
  }

  private def handleVehicleReservation(req: ReservationRequest, vehicleIdToReserve: Id[Vehicle]) = {
    val response = _currentLeg match {
      case Some(currentLeg) if req.departFrom.startTime < currentLeg.startTime =>
        ReservationResponse(req.requestId, Left(VehicleGone))
      case _ =>
        val tripReservations = passengerSchedule.schedule.from(req.departFrom).to(req.arriveAt).toVector
        val vehicleCap = beamServices.vehicles(vehicleIdToReserve).getType.getCapacity
        val fullCap = vehicleCap.getSeats + vehicleCap.getStandingRoom
        val hasRoom = tripReservations.forall { entry =>
          entry._2.riders.size < fullCap
        }
        if (hasRoom) {
          val legs = tripReservations.map(_._1)
          passengerSchedule.addPassenger(req.passengerVehiclePersonId, legs)
          ReservationResponse(req.requestId, Right(ReserveConfirmInfo(req.departFrom, req.arriveAt, req.passengerVehiclePersonId)))
        } else {
          ReservationResponse(req.requestId, Left(VehicleFull(vehicleIdToReserve)))
        }
    }
    response
  }
}
