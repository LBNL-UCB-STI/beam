package beam.agentsim.agents.vehicles

import akka.actor.{ActorContext, ActorRef, Props}
import akka.pattern.{pipe, _}
import akka.util.Timeout
import beam.agentsim.Resource
import beam.agentsim.agents.BeamAgent.{AnyState, BeamAgentData, BeamAgentState, Error, Initialized, Uninitialized}
import beam.agentsim.agents.vehicles.BeamVehicle.{AlightingConfirmation, AssignedCarrier, BecomeDriver, BecomeDriverSuccess, BoardingConfirmation, DriverAlreadyAssigned, EnterVehicle, ExitVehicle, Idle, Moving, ResetCarrier, UnbecomeDriver, UpdateTrajectory, VehicleFull, VehicleLocationRequest, VehicleLocationResponse}
import beam.agentsim.agents.{BeamAgent, InitializeTrigger, PersonAgent}
import beam.agentsim.agents.TriggerUtils._
import beam.agentsim.events.AgentsimEventsBus.MatsimEvent
import beam.agentsim.events.SpaceTime
import beam.agentsim.events.resources.{ReservationError, ReservationErrorCode}
import beam.agentsim.events.resources.ReservationErrorCode.ReservationErrorCode
import beam.agentsim.events.resources.vehicle._
import beam.agentsim.scheduler.{Trigger, TriggerWithId}
import beam.router.{BeamRouter, RoutingModel}
import beam.router.Modes.BeamMode
import beam.sim.{BeamServices, HasServices}
import org.matsim.api.core.v01.events.{PersonEntersVehicleEvent, PersonLeavesVehicleEvent}
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.utils.objectattributes.attributable.Attributes
import org.matsim.vehicles.{Vehicle, VehicleType}

import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.reflect.ClassTag
/**
  * @author dserdiuk
  */

abstract class Dimension

//object VehicleData {
//  case class VehicleDataImpl(vehicleTypeName: String, vehicleClassName: String,
//                             matSimVehicle: Vehicle, attributes: Attributes) extends VehicleData {
//    override def getType: VehicleType = matSimVehicle.getType
//
//    override def getId: Id[Vehicle] = matSimVehicle.getId
//  }
//
//}
//trait VehicleData extends BeamAgentData with Vehicle {
//
//}

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

  def buildActorName(matsimVehicle: Vehicle): String = {
    s"$ActorPrefixName${matsimVehicle.getType.getDescription.replaceAll(" ", "_")}-${matsimVehicle.getId.toString}"
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

  case class UpdateTrajectory(trajectory: Trajectory)
  case class StreetVehicle(id: Id[Vehicle], location: SpaceTime, mode: BeamMode, asDriver: Boolean)
  case class AssignedCarrier(carrierVehicleId: Id[Vehicle])
  case object ResetCarrier

}



/**
  * Defines common behavior for any vehicle. Communicate with PersonAgent
  * VehicleManager.
  * Passenger and driver can EnterVehicle and LeaveVehicle
  */
trait BeamVehicle extends Resource with  BeamAgent[BeamAgentData] with HasServices with Vehicle {
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
  var passengers: ListBuffer[Id[Vehicle]] = ListBuffer()
  var trajectory: Option[Trajectory] = None
  var pendingReservations: List[ReservationRequest] = List[ReservationRequest]()

  def location(time: Double): Future[SpaceTime] = {
    trajectory match {
      case Some(traj) =>
        carrier match {
          case Some(carrierVehicle) =>
            (carrierVehicle ? VehicleLocationRequest(time)).mapTo[SpaceTime].recover[SpaceTime] {
              case error: Throwable =>
                log.warning(s"Failed to get location of from carrier. ", error)
              traj.location(time)
            }(context.dispatcher)
          case None =>
            Future.successful(traj.location(time))
        }
      case None =>
        Future.failed(new RuntimeException("No trajectory defined."))
    }
  }

  def setDriver(newDriver: ActorRef) = {
    driver = Some(newDriver)
  }

  when(Idle) {
    case ev@Event(_, _) =>
      handleEvent(stateName, ev)
    case msg@_ =>
      logError(s"Unrecognized message ${msg}")
      goto(Error)
  }
  when(Moving) {
    case ev@Event(_, _) =>
      handleEvent(stateName, ev)
    case msg@_ =>
      logError(s"Unrecognized message ${msg}")
      goto(Error)
  }

  chainedWhen(Uninitialized){
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
      if(driver.isEmpty || driver.get == beamServices.agentRefs(newDriver.toString)) {
        if (driver.isEmpty) {
          driver = Some(beamServices.agentRefs(newDriver.toString))
          if (newDriver.isInstanceOf[Id[Person]]) beamServices.agentSimEventsBus.publish(MatsimEvent(new PersonEntersVehicleEvent(tick, newDriver.asInstanceOf[Id[Person]], id)))
        }
        // Important Note: the following works (asynchronously processing pending res's and then notifying driver of success)
        // only because we throw an exception when BecomeDriver fails. In other words, if the requesting
        // driver must register Success before assuming she is the driver, then we cannot send the PendingReservations as currently implemented
        // because that driver would not be ready to receive.
        val driverActor = driver.get
        sendPendingReservations(driverActor)
        driverActor  ! BecomeDriverSuccess(newPassengerSchedule, id)
      } else {
        //TODO throwing an excpetion is the simplest approach b/c agents need not wait for confirmation before assuming they are drivers, but futur versions of BEAM may seek to be robust to this condition
        throw new RuntimeException(s"BeamAgent $newDriver attempted to become driver of vehicle $id but driver ${driver.get} already assigned.")
        //        val beamAgent = sender()
        //        beamAgent ! DriverAlreadyAssigned(id, driver.get)
      }
      stay()
    case Event(ModifyPassengerSchedule(newPassengerSchedule,requestId), info) =>
      driver.get ! ModifyPassengerSchedule(newPassengerSchedule,requestId)
      stay()
    case Event(ModifyPassengerScheduleAck(requestId), info) =>
      driver.get ! ModifyPassengerScheduleAck(requestId)
      stay()

    case Event(UnbecomeDriver(tick, theDriver), info) =>
      if(driver.isEmpty) {
        //TODO throwing an excpetion is the simplest approach b/c agents need not wait for confirmation before assuming they are no longer drivers, but futur versions of BEAM may seek to be robust to this condition
        throw new RuntimeException(s"BeamAgent $theDriver attempted to Unbecome driver of vehicle $id but no driver in currently assigned.")
      }else{
        driver = None
        if(theDriver.isInstanceOf[Id[Person]])beamServices.agentSimEventsBus.publish(MatsimEvent(new PersonLeavesVehicleEvent(tick, theDriver.asInstanceOf[Id[Person]],id)))
      }
      stay()
    case Event(EnterVehicle(tick, newPassengerVehicle), info) =>
      val fullCapacity = getType.getCapacity.getSeats + getType.getCapacity.getStandingRoom
      if (passengers.size < fullCapacity){
        passengers += newPassengerVehicle.vehicleId
        driver.get ! BoardingConfirmation(newPassengerVehicle.vehicleId)
        beamServices.vehicleRefs.get(newPassengerVehicle.vehicleId).foreach{ vehiclePassengerRef =>
          vehiclePassengerRef ! AssignedCarrier(vehicleId)
        }
        beamServices.agentSimEventsBus.publish(MatsimEvent(new PersonEntersVehicleEvent(tick, newPassengerVehicle.personId,id)))
      } else {
        val leftSeats = fullCapacity - passengers.size
        val beamAgent = sender()
        beamAgent ! VehicleFull(id)
      }
      stay()
    case Event(ExitVehicle(tick, passengerVehicleId), info) =>
      passengers -= passengerVehicleId.vehicleId
      driver.get ! AlightingConfirmation(passengerVehicleId.vehicleId)
      beamServices.vehicleRefs.get(passengerVehicleId.vehicleId).foreach{ vehiclePassengerRef =>
        vehiclePassengerRef ! ResetCarrier
      }
      logDebug(s"Passenger ${passengerVehicleId} alighted from vehicleId=$id")
      beamServices.agentSimEventsBus.publish(MatsimEvent(new PersonLeavesVehicleEvent(tick, passengerVehicleId.personId,id)))
      stay()
    case Event(UpdateTrajectory(newTrajectory), info) =>
      trajectory match {
        case Some(traj) =>
          traj.append(newTrajectory)
        case None =>
          trajectory = Some(newTrajectory)
      }
      stay()
  }

  chainedWhen(AnyState){
    case Event(VehicleLocationRequest(time), _) =>
      sender() ! VehicleLocationResponse(id, location(time))
      stay()
    case Event(AssignedCarrier(carrierVehicleId), _) =>
      carrier = beamServices.vehicleRefs.get(carrierVehicleId)
      stay()
    case Event(ResetCarrier, _) =>
      carrier = None
      stay()
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
    if(!nestedVehicles.isEmpty && nestedVehicles.head == vehicle){
      VehicleStack(nestedVehicles)
    }else{
      VehicleStack(vehicle +: nestedVehicles)
    }
  }
  def penultimateVehicle(): Id[Vehicle] = {
    if(nestedVehicles.size < 2)throw new RuntimeException("Attempted to access penultimate vehilce when 1 or 0 are in the vehicle stack.")
    nestedVehicles(1)
  }
  def outermostVehicle(): Id[Vehicle] = {
    nestedVehicles(0)
  }
  def pop(): VehicleStack = {
    VehicleStack(nestedVehicles.tail)
  }
}


