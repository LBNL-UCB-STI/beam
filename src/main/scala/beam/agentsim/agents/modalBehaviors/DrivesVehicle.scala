package beam.agentsim.agents.modalBehaviors

import beam.agentsim.agents.BeamAgent.BeamAgentData
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.agents.modalBehaviors.DrivesVehicle.{EndLegTrigger, NotifyLegEnd, NotifyLegStart, StartLegTrigger}
import beam.agentsim.agents.util.{AggregatorFactory, MultipleAggregationResult}
import beam.agentsim.agents.vehicles.BeamVehicle.{AlightingConfirmation, BeamVehicleIdAndRef, BecomeDriver, BecomeDriverSuccess, BoardingConfirmation, UnbecomeDriver, UpdateTrajectory}
import beam.agentsim.agents.vehicles.PassengerSchedule
import beam.agentsim.agents.vehicles.{BeamVehicle, VehicleData}
import beam.agentsim.agents.{BeamAgent, PersonAgent, TriggerShortcuts}
import beam.agentsim.events.resources.vehicle._
import beam.agentsim.scheduler.{Trigger, TriggerWithId}
import beam.router.RoutingModel.BeamLeg
import beam.sim.HasServices
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

import scala.collection.mutable.HashSet


/**
  * @author dserdiuk on 7/29/17.
  */
object DrivesVehicle {
  case class StartLegTrigger(tick: Double, beamLeg: BeamLeg) extends Trigger
  case class EndLegTrigger(tick: Double, beamLeg: BeamLeg) extends Trigger
  case class NotifyLegEnd(tick: Double)
  case class NotifyLegStart(tick: Double)
}

trait DrivesVehicle[T <: BeamAgentData] extends  TriggerShortcuts with HasServices with AggregatorFactory {
  this: BeamAgent[T] =>

  //TODO: double check that mutability here is legit espeically with the schedules passed in
  protected var passengerSchedule: PassengerSchedule = PassengerSchedule()

  protected var _currentTriggerId: Option[Long] = None
  protected var _currentTick: Option[Double] = None
  protected var _currentLeg: Option[BeamLeg] = None
  protected var _currentVehicle: Option[BeamVehicleIdAndRef] = None
  protected val _awaitingBoardConfirmation: HashSet[Id[Vehicle]] = HashSet()
  protected val _awaitingAlightConfirmation: HashSet[Id[Vehicle]] = HashSet()

  def nextBeamLeg():  BeamLeg

  chainedWhen(Moving) {
    case Event(TriggerWithId(EndLegTrigger(tick, completedLeg), triggerId), agentInfo) =>
      //we have just completed a leg
      log.debug(s"Received EndLeg for beamVehicleId=${_currentVehicle.get.id}, started Boarding/Alighting   ")
      _currentTriggerId = Some(triggerId)
      _currentTick = Some(tick)
      passengerSchedule.schedule.get(completedLeg) match {
        case Some(manifest) =>
          _awaitingAlightConfirmation ++= manifest.alighters
          manifest.riders.foreach(passenger => beamServices.vehicleRefs(passenger) ! NotifyLegEnd)
          stay()
        case None =>
          log.error(s"Driver ${id} did not find a manifest for BeamLeg ${_currentLeg}")
          goto(BeamAgent.Error)
      }
    case Event(ReservationRequestWithVehicle(req, vehicleData: VehicleData), _) =>
      val proxyVehicleAgent = sender()
      require(passengerSchedule.schedule.nonEmpty, "Driver needs to init list of stops")
      //      val response: ReservationResponse = handleVehicleReservation(req, vehicleData, proxyVehicleAgent)
      //        req.passenger ! response
      stay()
    case Event(AlightingConfirmation(vehicleId), agentInfo) =>
      _awaitingAlightConfirmation -= vehicleId
      if (_awaitingAlightConfirmation.isEmpty) {
        processNextLegOrCompleteMission()
      } else {
        stay()
      }
  }
  chainedWhen(Waiting) {
    case Event(BecomeDriverSuccess(newPassengerSchedule), info) =>
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
          manifest.riders.foreach(passenger => beamServices.vehicleRefs(passenger) ! NotifyLegStart)
          _currentVehicle.get.ref ! UpdateTrajectory(newLeg.travelPath.toTrajectory)
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
      val nextLeg: BeamLeg  = nextBeamLeg()
      _currentLeg = Option(nextLeg)
      stay() replying scheduleOne[ScheduleBeginLegTrigger](nextLeg.startTime, agent = self)

  }

  private def processNextLegOrCompleteMission() = {
    val theTriggerId = _currentTriggerId.get
    _currentTriggerId = None
    val theTick = _currentTick.get
    _currentTick = None

    passengerSchedule.schedule.remove(passengerSchedule.schedule.firstKey)
    if(passengerSchedule.schedule.size > 0){
      _currentLeg = Some(passengerSchedule.schedule.firstKey)
      goto(Waiting) replying completed(theTriggerId, schedule[StartLegTrigger](_currentLeg.get.startTime,self))
    }else{
      _currentVehicle.get.ref ! UnbecomeDriver(theTick, id)
      goto(Waiting) replying completed(theTriggerId, schedule[CompleteDrivingMissionTrigger](theTick,self))
    }
  }

//  private def processBoarding(triggerTick: Double, completedLeg: BeamLeg, transferVehicleAgent: ActorRef,
//                              passengersToPickUp: ListBuffer[ReservationLogEntry]) = {
//    val boardingNotice = new BoardingNotice(completedLeg)
//    val boardingRequests = passengersToPickUp.map(p => (p.passenger, List(boardingNotice))).toMap
//    aggregateResponsesTo(transferVehicleAgent, boardingRequests) { case result: MultipleAggregationResult =>
//      val passengers = result.responses.collect {
//        case (passenger, _) =>
//          passenger
//      }
//      EnterVehicleTrigger(triggerTick, transferVehicleAgent, None, Option(passengers.toList), Option(boardingNotice.noticeId))
//    }
//  }
//
//  /**
//    *
//    * @param triggerTick when alighting is taking place
//    * @param completedLeg stop leg
//    * @param transferVehicleAgent vehicle agent of bus/car/train that does alighting
//    * @param passengersToDropOff list of passenger to be alighted
//    * @return
//    */
//  private def processAlighting(triggerTick: Double, completedLeg: BeamLeg, transferVehicleAgent: ActorRef,
//                               passengersToDropOff: ListBuffer[ReservationLogEntry]): Unit = {
//    val alightingNotice = new AlightingNotice(completedLeg)
//    val alightingRequests = passengersToDropOff.map(p => (p.passenger, List(alightingNotice))).toMap
//    aggregateResponsesTo(transferVehicleAgent, alightingRequests) { case result: MultipleAggregationResult =>
//      val passengers = result.responses.collect {
//        case (passenger, _) =>
//          passenger
//      }
//      LeaveVehicleTrigger(triggerTick, transferVehicleAgent, None, Option(passengers.toList), Option(alightingNotice.noticeId))
//    }
//  }

//  private def handleVehicleReservation(req: ReservationRequest, vehicleData: VehicleData, vehicleAgent: ActorRef) = {
//    val response = _currentLeg match {
//      case Some(currentLeg) if req.departFrom.startTime < currentLeg.startTime =>
//        ReservationResponse(req.requestId, Left(VehicleGone))
//      case _ =>
//        val tripReservations = passengerSchedule.schedule.from(req.departFrom).to(req.arriveAt)
//        val vehicleCap = vehicleData.fullCapacity
//        val hasRoom = tripReservations.forall { entry =>
//          entry._2.size < vehicleCap
//        }
//        if (hasRoom) {
//          val reservation = new ReservationLogEntry(req)
//          tripReservations.values.foreach { entry =>
//            entry.append(reservation)
//          }
//          ReservationResponse(req.requestId, Right(new ReserveConfirmInfo(req, vehicleAgent)))
//        } else {
//          ReservationResponse(req.requestId, Left(VehicleUnavailable))
//        }
//    }
//    response
//  }
}
