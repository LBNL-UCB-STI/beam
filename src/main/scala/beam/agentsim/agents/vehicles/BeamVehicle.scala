package beam.agentsim.agents.vehicles

import akka.actor.FSM.Failure
import akka.actor.{ActorRef, Props}
import akka.pattern._
import beam.agentsim.Resource
import beam.agentsim.Resource.{AssignManager, TellManagerResourceIsAvailable}
import beam.agentsim.agents.BeamAgent._
import beam.agentsim.agents.TriggerUtils._
import beam.agentsim.agents.modalBehaviors.CancelReservationWithVehicle
import beam.agentsim.agents.vehicles.BeamVehicle.{AlightingConfirmation, AppendToTrajectory, AssignedCarrier, BecomeDriver, BecomeDriverSuccess, BoardingConfirmation, EnterVehicle, ExitVehicle, Idle, Moving, RemovePassengerFromTrip, ResetCarrier, UnbecomeDriver, VehicleFull, VehicleLocationRequest, VehicleLocationResponse}
import beam.agentsim.agents.{BeamAgent, InitializeTrigger}
import beam.agentsim.events.SpaceTime
import beam.agentsim.events.resources.ReservationErrorCode.ReservationErrorCode
import beam.agentsim.events.resources.vehicle._
import beam.agentsim.events.resources.{ReservationError, ReservationErrorCode}
import beam.agentsim.scheduler.TriggerWithId
import beam.router.Modes.BeamMode
import beam.router.RoutingModel.BeamPath
import beam.sim.{BeamServices, HasServices}
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.{PersonEntersVehicleEvent, PersonLeavesVehicleEvent}
import org.matsim.api.core.v01.population.Person
import org.matsim.utils.objectattributes.attributable.Attributes
import org.matsim.vehicles.{Vehicle, VehicleType}

import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
/**
  * @author dserdiuk
  */

abstract class Dimension

trait BeamVehicleObject {
  def props(beamServices: BeamServices, vehicleId: Id[Vehicle], matSimVehicle: Vehicle, powertrain: Powertrain): Props
}

object BeamVehicle {

  val ActorPrefixName = "vehicle-"

  case class BeamVehicleIdAndRef(id: Id[Vehicle], ref: ActorRef)

  object BeamVehicleIdAndRef {
    def apply(tup: (Id[Vehicle], ActorRef)): BeamVehicleIdAndRef = new BeamVehicleIdAndRef(tup._1, tup._2)
  }

  case object Moving extends BeamAgentState {
    override def identifier = "Moving"
  }
  case object Idle extends BeamAgentState {
    override def identifier = "Idle"
  }

  def energyPerUnitByType(vehicleTypeId: Id[VehicleType]): Double = {
    //TODO: add energy type registry
      0.0
  }

  def noSpecialChars(theString: String) = theString.replaceAll("[\\\\|\\\\^]+", ":")

  def buildActorName(matsimVehicle: Vehicle): String = {
    s"$ActorPrefixName${matsimVehicle.getType.getDescription.replaceAll(" ", "_")}-${noSpecialChars(matsimVehicle.getId.toString)}"
  }

  implicit def actorRef2Id(actorRef: ActorRef): Option[Id[Vehicle]] = {
    if (actorRef.path.name.startsWith(ActorPrefixName)) {
      Some(Id.create(actorRef.path.name.substring(ActorPrefixName.length), classOf[Vehicle]))
    } else {
      None
    }
  }

  case class VehicleLocationRequest(time: Double)
  case class VehicleLocationResponse(vehicleId: Id[Vehicle], spaceTime: Future[SpaceTime])

  case class AlightingConfirmation(vehicleId: Id[Vehicle])
  case class BoardingConfirmation(vehicleId: Id[Vehicle])

  case class BecomeDriver(tick: Double, driver: Id[_], passengerSchedule: Option[PassengerSchedule] = None)
  case class UnbecomeDriver(tick: Double, driver: Id[_])
  case class BecomeDriverSuccess(passengerSchedule: Option[PassengerSchedule], inVehicleId: Id[Vehicle])
  case object BecomeDriverSuccessAck
  case class DriverAlreadyAssigned(vehicleId: Id[Vehicle], currentDriver: ActorRef)

  case class EnterVehicle(tick: Double, passengerVehicle : VehiclePersonId)
  case class ExitVehicle(tick: Double, passengerVehicle : VehiclePersonId)
  case class VehicleFull(vehicleId: Id[Vehicle]) extends ReservationError {
    override def errorCode: ReservationErrorCode = ReservationErrorCode.ResourceCapacityExhausted
  }

  case class AppendToTrajectory(beamPath: BeamPath)
  case class StreetVehicle(id: Id[Vehicle], location: SpaceTime, mode: BeamMode, asDriver: Boolean)
  case class AssignedCarrier(carrierVehicleId: Id[Vehicle])
  case object ResetCarrier

  case class RemovePassengerFromTrip(passId: VehiclePersonId)

}



/**
  * Defines common behavior for any vehicle. Communicate with PersonAgent
  * VehicleManager.
  * Passenger and driver can EnterVehicle and LeaveVehicle
  */
trait BeamVehicle extends BeamAgent[BeamAgentData] with Resource[Vehicle] with HasServices with Vehicle {
  override val id: Id[Vehicle]
  override def logPrefix(): String = s"BeamVehicle:$id "

  def matSimVehicle: Vehicle
  def attributes: Attributes
  def vehicleTypeName: String
  def vehicleClassName: String

  val vehicleId: Id[Vehicle]
  val data: BeamAgentData
  var powerTrain: Powertrain

  /**
    * The vehicle that is carrying this one. Like ferry or truck may carry a car and like a car carries a human body.
    */
  var carrier: Option[ActorRef] = None
  var driver: Option[ActorRef] = None
  /**
    * The actor managing this Vehicle
    */
  override var manager: Option[ActorRef] = None
  var passengers: ListBuffer[Id[Vehicle]] = ListBuffer()
  var lastVisited:  SpaceTime = SpaceTime.zero
  var pendingReservations: List[ReservationRequest] = List[ReservationRequest]()

  def location(time: Double): Future[SpaceTime] = {
    carrier match {
      case Some(carrierVehicle) =>
        (carrierVehicle ? VehicleLocationRequest(time)).mapTo[SpaceTime].recover[SpaceTime] {
          case error: Throwable =>
            log.warning(s"Failed to get location from carrier ${carrierVehicle.path.name}. ", error)
            lastVisited
        }
      case None =>
        if (time > lastVisited.time) {
          logWarn(s"Requested time ${time} is in future. return lastVisited")
        }
        Future.successful(lastVisited)
    }
  }

  def setDriver(newDriver: ActorRef) = {
    driver = Some(newDriver)
  }

  when(Idle) {
    case ev@Event(_, _) =>
      handleEvent(stateName, ev)
    case msg@_ =>
      stop(Failure(s"From state Idle: Unrecognized message $msg"))
  }
  when(Moving) {
    case ev@Event(_, _) =>
      handleEvent(stateName, ev)
    case msg@_ =>
      stop(Failure(s"From state Moving: Unrecognized message $msg"))
  }

  chainedWhen(Uninitialized){
    case Event(AssignManager(managerRef),_)=>
      manager = Some(managerRef)
      stay()
    case Event(TriggerWithId(InitializeTrigger(tick), triggerId), _) =>
      log.debug(s" $id has been initialized, going to Idle state")
      goto(Idle) replying completed(triggerId)
  }

  private def sendPendingReservations(driverActor: ActorRef) = {
    if (pendingReservations.nonEmpty) {
      log.info(s"Sending pending ${pendingReservations.size} reservation request(s) to driver ${driverActor.path.name}")
      pendingReservations.foreach { reservation =>
        driverActor ! ReservationRequestWithVehicle(reservation, id)
      }
      pendingReservations = List()
    }
  }

  chainedWhen(Idle) {

    case Event(BecomeDriver(tick, newDriver, newPassengerSchedule), info) =>
      if (driver.isEmpty || driver.get == beamServices.agentRefs(newDriver.toString)) {
        if (driver.isEmpty) {
          driver = Some(beamServices.agentRefs(newDriver.toString))
          newDriver match {
            case personId: Id[Person] => context.system.eventStream.publish(new PersonEntersVehicleEvent(tick, personId, id))
            case _ =>
          }
        }
        // Important Note: the following works (asynchronously processing pending res's and then notifying driver of success)
        // only because we throw an exception when BecomeDriver fails. In other words, if the requesting
        // driver must register Success before assuming she is the driver, then we cannot send the PendingReservations as currently implemented
        // because that driver would not be ready to receive.
        val driverActor = driver.get
        sendPendingReservations(driverActor)
        driverActor ! BecomeDriverSuccess(newPassengerSchedule, id)
      } else {
        stop(Failure(s"BeamAgent $newDriver attempted to become driver of vehicle $id but driver ${driver.get} already assigned."))
      }
      stay()
    case Event(ModifyPassengerSchedule(newPassengerSchedule, requestId), info) =>
      driver.get ! ModifyPassengerSchedule(newPassengerSchedule, requestId)
      stay()
    case Event(ModifyPassengerScheduleAck(requestId), info) =>
      driver.get ! ModifyPassengerScheduleAck(requestId)
      stay()

    case Event(TellManagerResourceIsAvailable(whenWhere:SpaceTime),_)=>
      notifyManagerResourceIsAvailable(whenWhere)
      stay()
    case Event(UnbecomeDriver(tick, theDriver), info) =>
      if (driver.isEmpty) {
        stop(Failure(s"BeamAgent $theDriver attempted to Unbecome driver of vehicle $id but no driver in currently assigned."))
      } else {
        driver = None
        theDriver match {
          case personId: Id[Person] => context.system.eventStream.publish(new PersonLeavesVehicleEvent(tick, personId, id))
          case _ =>
        }
      }
      stay()
    case Event(EnterVehicle(tick, newPassengerVehicle), info) =>
      val fullCapacity = getType.getCapacity.getSeats + getType.getCapacity.getStandingRoom
      if (passengers.size < fullCapacity) {
        passengers += newPassengerVehicle.vehicleId
        driver.get ! BoardingConfirmation(newPassengerVehicle.vehicleId)
        beamServices.vehicleRefs.get(newPassengerVehicle.vehicleId).foreach { vehiclePassengerRef =>
          vehiclePassengerRef ! AssignedCarrier(vehicleId)
        }
        context.system.eventStream.publish(new PersonEntersVehicleEvent(tick, newPassengerVehicle.personId, id))
      } else {
        val leftSeats = fullCapacity - passengers.size
        val beamAgent = sender()
        beamAgent ! VehicleFull(id)
      }
      stay()
    case Event(ExitVehicle(tick, passengerVehicleId), info) =>
      passengers -= passengerVehicleId.vehicleId
      driver.get ! AlightingConfirmation(passengerVehicleId.vehicleId)
      beamServices.vehicleRefs.get(passengerVehicleId.vehicleId).foreach { vehiclePassengerRef =>
        vehiclePassengerRef ! ResetCarrier
      }

      logDebug(s"Passenger ${passengerVehicleId} alighted from vehicleId=$id")
      context.system.eventStream.publish(new PersonLeavesVehicleEvent(tick, passengerVehicleId.personId, id))
      stay()
    case Event(Finish, _) =>
      stop
  }

  chainedWhen(AnyState) {
    case Event(VehicleLocationRequest(time), _) =>
      sender() ! VehicleLocationResponse(id, location(time))
      stay()
    case Event(AssignedCarrier(carrierVehicleId), _) =>
      carrier = beamServices.vehicleRefs.get(carrierVehicleId)
      stay()
    case Event(ResetCarrier, _) =>
      carrier = None
      stay()
    case Event(AppendToTrajectory(newSegments), info) =>
      lastVisited = newSegments.getEndPoint()
      stay()
    case Event(a: RemovePassengerFromTrip, _) => {
      driver.foreach { d =>
        d ! a
      }
      stay()
    }
    case Event(req: CancelReservationWithVehicle,_) => {
      pendingReservations = pendingReservations.filterNot(x=>x.passengerVehiclePersonId.equals(req.vehiclePersonId))
      driver.foreach{ d=>
        d ! RemovePassengerFromTrip(req.vehiclePersonId)
      }
      stay()
    }

    case Event(request: ReservationRequest, _) =>
      driver match {
        case Some(driverActor) =>
          driverActor ! ReservationRequestWithVehicle(request, id)
        case None =>
          pendingReservations = pendingReservations :+ request
      }
      stay()
    case Event(any, _) =>
      logError(s"Unhandled event: $id $any $data")
      stay()
  }
}

/**
  * VehicleDataImpl contains Attributes. These enumerations are defined to simplify extensibility of VehicleData
  */
object VehicleAttributes extends Enumeration {

  val capacity = Value("capacity")

  object Electric extends Enumeration {
    val electricEnergyConsumptionModelClassname = Value("electricEnergyConsumptionModelClassname")
    val batteryCapacityInKWh = Value("batteryCapacityInKWh")
    val maxDischargingPowerInKW = Value("maxDischargingPowerInKW")
    val maxLevel2ChargingPowerInKW = Value("maxLevel2ChargingPowerInKW")
    val maxLevel3ChargingPowerInKW = Value("maxLevel3ChargingPowerInKW")
    val targetCoefA = Value("targetCoefA")
    val targetCoefB = Value("targetCoefB")
    val targetCoefC = Value("targetCoefC")
  }

  object Gasoline extends Enumeration {
    val gasolineFuelConsumptionRateInJoulesPerMeter = Value("gasolineFuelConsumptionRateInJoulesPerMeter")
    val fuelEconomyInKwhPerMile = Value("fuelEconomyInKwhPerMile")
    val equivalentTestWeight = Value("equivalentTestWeight")
  }
}

case class VehicleStack(nestedVehicles: Vector[Id[Vehicle]] = Vector()){
  def isEmpty = nestedVehicles.isEmpty

  def pushIfNew(vehicle: Id[Vehicle]) = {
    if (!nestedVehicles.isEmpty && nestedVehicles.head == vehicle) {
      VehicleStack(nestedVehicles)
    } else {
      VehicleStack(vehicle +: nestedVehicles)
    }
  }

  def penultimateVehicle(): Id[Vehicle] = {
    if (nestedVehicles.size < 2) throw new RuntimeException("Attempted to access penultimate vehilce when 1 or 0 are in the vehicle stack.")
    nestedVehicles(1)
  }

  def outermostVehicle(): Id[Vehicle] = {
    nestedVehicles(0)
  }
  def pop(): VehicleStack = {
    VehicleStack(nestedVehicles.tail)
  }
}


