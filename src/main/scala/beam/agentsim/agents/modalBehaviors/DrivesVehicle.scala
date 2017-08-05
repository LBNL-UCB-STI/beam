package beam.agentsim.agents.modalBehaviors

import akka.actor.ActorRef
import beam.agentsim.agents.BeamAgent.BeamAgentData
import beam.agentsim.agents.PersonAgent.{Moving, PersonData, ScheduleBeginLegTrigger}
import beam.agentsim.agents.modalBehaviors.DrivesVehicle.{EndLegTrigger, StartLegTrigger}
import beam.agentsim.agents.util.{AggregatorFactory, MultipleAggregationResult}
import beam.agentsim.agents.vehicles.BeamVehicle.BeamVehicleIdAndRef
import beam.agentsim.agents.vehicles.VehicleData._
import beam.agentsim.agents.vehicles.PassengerSchedule
import beam.agentsim.agents.vehicles.{BeamVehicle, EnterVehicleTrigger, LeaveVehicleTrigger, VehicleData}
import beam.agentsim.agents.{BeamAgent, PersonAgent, TriggerShortcuts}
import beam.agentsim.events.resources.vehicle._
import beam.agentsim.scheduler.{Trigger, TriggerWithId}
import beam.router.RoutingModel.BeamLeg
import beam.sim.HasServices

import scala.collection.mutable.ListBuffer

/**
  * @author dserdiuk on 7/29/17.
  */
object DrivesVehicle {
  case class StartLegTrigger(tick: Double, beamLeg: BeamLeg) extends Trigger
  case class EndLegTrigger(tick: Double, beamLeg: BeamLeg) extends Trigger
}

trait DrivesVehicle[T <: BeamAgentData] extends  TriggerShortcuts with HasServices with AggregatorFactory {
  this: BeamAgent[T] =>

  //TODO: init log with empty lists of stops according to vehicle trip/schedule route
  protected lazy val passengerSchedule: PassengerSchedule = PassengerSchedule()

  protected var _currentLeg: Option[BeamLeg] = None
  protected var _currentVehicle: Option[BeamVehicleIdAndRef] = None

  def nextBeamLeg():  BeamLeg

  chainedWhen(Moving) {
    case Event(TriggerWithId(EndLegTrigger(tick, completedLeg), triggerId), agentInfo) =>
      //we have just completed a leg
      log.debug(s"Received completed leg for beamVehicleId=${_currentVehicle.get.id}, started Boarding/Alighting   ")
//      passengerSchedule.schedule.get(completedLeg) match {
//        case Some(passengerReservations) =>
//            val passengersToDropOff  = passengerReservations.filter(_.arriveAt == completedLeg)
//            processAlighting(tick, completedLeg, beamVehicleRef, passengersToDropOff)
//
//            val passengersToPickUp = passengerReservations.filter(_.departFrom == completedLeg)
//            processBoarding(tick, completedLeg, beamVehicleRef, passengersToPickUp)
//          }
//          }
//        case None =>
//          // do nothing
//      }
      stay()
    case Event(ReservationRequestWithVehicle(req, vehicleData: VehicleData), _) =>
      val proxyVehicleAgent = sender()
      require(passengerSchedule.schedule.nonEmpty, "Driver needs to init list of stops")
//      val response: ReservationResponse = handleVehicleReservation(req, vehicleData, proxyVehicleAgent)
//        req.passenger ! response
        stay()
    case Event(AlightingConfirmation(noticeId), agentInfo) =>
      stay()

    case Event(BoardingConfirmation(noticeId), agentInfo) =>
      val nextLeg: BeamLeg  = nextBeamLeg()
      _currentLeg = Option(nextLeg)
      stay() replying scheduleOne[ScheduleBeginLegTrigger](nextLeg.startTime, agent = self)

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
