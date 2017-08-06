package beam.agentsim.agents.modalBehaviors

import akka.actor.ActorRef
import beam.agentsim.agents.BeamAgent.BeamAgentData
import beam.agentsim.agents.PersonAgent.{PersonData, ScheduleBeginLegTrigger}
import beam.agentsim.agents.modalBehaviors.DrivesVehicle.CompleteLegTrigger
import beam.agentsim.agents.modalBehaviors.DrivesVehicle._
import beam.agentsim.agents.util.{AggregatorFactory, MultipleAggregationResult}
import beam.agentsim.agents.vehicles.BeamVehicle._
import beam.agentsim.agents.vehicles.VehicleData._
import beam.agentsim.agents.vehicles.{BeamVehicle, EnterVehicleTrigger, LeaveVehicleTrigger, VehicleData}
import beam.agentsim.agents.{BeamAgent, PersonAgent, TriggerShortcuts}
import beam.agentsim.events.resources.vehicle._
import beam.agentsim.scheduler.{Trigger, TriggerWithId}
import beam.router.RoutingModel.BeamLeg
import beam.sim.HasServices

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

/**
  * @author dserdiuk on 7/29/17.
  */
object DrivesVehicle {
  case class CompleteLegTrigger(tick: Double, beamLeg: BeamLeg) extends Trigger
  implicit val beamLegOrdering: Ordering[BeamLeg] = Ordering.by(_.startTime)

}

trait DrivesVehicle[T <: BeamAgentData] extends  TriggerShortcuts with HasServices with AggregatorFactory {
  this: BeamAgent[T] =>

  type PassengerLogStorage = mutable.TreeMap[BeamLeg, mutable.ListBuffer[ReservationLogEntry]]

  case class ReservationLogEntry(departFrom: BeamLeg, arriveAt: BeamLeg,  passenger: ActorRef) {
    def this(r: ReservationRequest) =  this(r.departFrom, r.arriveAt, r.passenger)
  }
  //TODO: init log with empty lists of stops according to vehicle trip/schedule route
  protected lazy val passengersLog: PassengerLogStorage = mutable.TreeMap[BeamLeg, mutable.ListBuffer[ReservationLogEntry]]()(beamLegOrdering)

  protected var _currentLeg: Option[BeamLeg] = None

  def nextBeamLeg():  BeamLeg

  chainedWhen(PersonAgent.Driving) {
    case Event(TriggerWithId(CompleteLegTrigger(tick, completedLeg), triggerId), agentInfo) =>
      //we have just completed a leg
      passengersLog.get(completedLeg) match {
        case Some(passengerReservations) =>
        /**
          * XXX: assume completedLeg is pickup and drop-off point
          * 1. driver -> each passenger AlightNotice(noticeId)
          * 2. passenger -> BeamVehicle ExitVehicle -> aggregate ;
          * 3. BeamVehicle -> Driver AlightingConfirmation(noticeId, passengers)
          * 4.
          */
          completedLeg.beamVehicleId.foreach { beamVehicleId =>
            import context._
            BeamVehicle.vehicleId2actorRef(beamVehicleId)(context, timeout).foreach{ beamVehicleRef =>
              log.debug(s"Received completed leg for beamVehicleId=$beamVehicleId , started Boarding/Alighting   ")
              val passengersToDropOff  = passengerReservations.filter(_.arriveAt == completedLeg)
              processAlighting(tick, completedLeg, beamVehicleRef, passengersToDropOff)

              val passengersToPickUp = passengerReservations.filter(_.departFrom == completedLeg)
              processBoarding(tick, completedLeg, beamVehicleRef, passengersToPickUp)
            }
          }
        case None =>
          // do nothing
      }
      //TODO: cleanup passengersLog from alighted passengers
      stay()
    case Event(ReservationRequestWithVehicle(req, vehicleData: VehicleData), _) =>
      val proxyVehicleAgent = sender()
      require(passengersLog.nonEmpty, "Driver needs to init list of stops")
      val response: ReservationResponse = handleVehicleReservation(req, vehicleData, proxyVehicleAgent)
        req.passenger ! response
        stay()
    case Event(AlightingConfirmation(noticeId), agentInfo) =>
      stay()

    case Event(BoardingConfirmation(noticeId), agentInfo) =>
      val nextLeg: BeamLeg  = nextBeamLeg()
      _currentLeg = Option(nextLeg)
      stay() replying scheduleOne[ScheduleBeginLegTrigger](nextLeg.startTime, agent = self)

  }

  private def processBoarding(triggerTick: Double, completedLeg: BeamLeg, transferVehicleAgent: ActorRef,
                              passengersToPickUp: ListBuffer[ReservationLogEntry]) = {
    val boardingNotice = new BoardingNotice(completedLeg)
    val boardingRequests = passengersToPickUp.map(p => (p.passenger, List(boardingNotice))).toMap
    aggregateResponsesTo(transferVehicleAgent, boardingRequests) { case result: MultipleAggregationResult =>
      val passengers = result.responses.collect {
        case (passenger, _) =>
          passenger
      }
      EnterVehicleTrigger(triggerTick, transferVehicleAgent, None, Option(passengers.toList), Option(boardingNotice.noticeId))
    }
  }

  /**
    *
    * @param triggerTick when alighting is taking place
    * @param completedLeg stop leg
    * @param transferVehicleAgent vehicle agent of bus/car/train that does alighting
    * @param passengersToDropOff list of passenger to be alighted
    * @return
    */
  private def processAlighting(triggerTick: Double, completedLeg: BeamLeg, transferVehicleAgent: ActorRef,
                               passengersToDropOff: ListBuffer[ReservationLogEntry]): Unit = {
    val alightingNotice = new AlightingNotice(completedLeg)
    val alightingRequests = passengersToDropOff.map(p => (p.passenger, List(alightingNotice))).toMap
    aggregateResponsesTo(transferVehicleAgent, alightingRequests) { case result: MultipleAggregationResult =>
      val passengers = result.responses.collect {
        case (passenger, _) =>
          passenger
      }
      LeaveVehicleTrigger(triggerTick, transferVehicleAgent, None, Option(passengers.toList), Option(alightingNotice.noticeId))
    }
  }

  private def handleVehicleReservation(req: ReservationRequest, vehicleData: VehicleData, vehicleAgent: ActorRef) = {
    val response = _currentLeg match {
      case Some(currentLeg) if req.departFrom.startTime < currentLeg.startTime =>
        ReservationResponse(req.requestId, Left(VehicleGone))
      case _ =>
        val tripReservations = passengersLog.from(req.departFrom).to(req.arriveAt)
        val vehicleCap = vehicleData.fullCapacity
        val hasRoom = tripReservations.forall { entry =>
          entry._2.size < vehicleCap
        }
        if (hasRoom) {
          val reservation = new ReservationLogEntry(req)
          tripReservations.values.foreach { entry =>
            entry.append(reservation)
          }
          ReservationResponse(req.requestId, Right(new ReserveConfirmInfo(req, vehicleAgent)))
        } else {
          ReservationResponse(req.requestId, Left(VehicleUnavailable))
        }
    }
    response
  }
}
