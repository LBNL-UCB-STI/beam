package beam.agentsim.agents.vehicles

import akka.actor.{ActorContext, ActorRef, Props}
import akka.pattern.{pipe, _}
import akka.util.Timeout
import beam.agentsim.Resource
import beam.agentsim.agents.BeamAgent.{BeamAgentData, BeamAgentState, Initialized, Uninitialized}
import beam.agentsim.agents.vehicles.BeamVehicle.{AlightingConfirmation, BecomeDriver, BecomeDriverSuccess, BoardingConfirmation, DriverAlreadyAssigned, EnterVehicle, ExitVehicle, GetVehicleLocationEvent, Idle, Moving, UpdateTrajectory, VehicleFull}
import beam.agentsim.agents.{BeamAgent, InitializeTrigger, TriggerShortcuts}
import beam.agentsim.events.SpaceTime
import beam.agentsim.events.resources.vehicle._
import beam.agentsim.scheduler.{Trigger, TriggerWithId}
import beam.sim.HasServices
import org.matsim.api.core.v01.Id
import org.matsim.utils.objectattributes.attributable.Attributes
import org.matsim.vehicles.{Vehicle, VehicleType}

import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
/**
  * @author dserdiuk
  */

abstract class Dimension

object VehicleData {
  case class VehicleDataImpl(vehicleTypeName: String, vehicleClassName: String,
                             matSimVehicle: Vehicle, attributes: Attributes) extends VehicleData {
    override def getType: VehicleType = matSimVehicle.getType

    override def getId: Id[Vehicle] = matSimVehicle.getId
  }

  implicit def vehicle2vehicleData(vehicle: Vehicle): VehicleData = {
    val vdata = VehicleDataImpl(vehicle.getType.getDescription,
      vehicle.getClass.getName, vehicle, new Attributes())
    vdata
  }
  implicit class SmartVehicle(vehicle: VehicleData) {
    def fullCapacity: Integer = vehicle.getType.getCapacity.getSeats + vehicle.getType.getCapacity.getStandingRoom
  }
}
trait VehicleData extends BeamAgentData with Vehicle {
  /**
    * It's pretty general name of type of vehicle.
    * It could be a model name of particular brand as well as vehicle class: sedan, truck, bus etc.
    * The key point this type need to be unique
    * @return
    */
  def vehicleTypeName: String

  /**
    * MATSim vehicle vehicle implementation class
    * @return
    */
  def vehicleClassName: String
}

object BeamVehicle {
  val ActorPrefixName = "vehicle-"

  def props(vehicleId: Id[Vehicle], matSimVehicle: Vehicle, powertrain: Powertrain) = {
    Props(classOf[BeamVehicle], vehicleId, VehicleData.vehicle2vehicleData(matSimVehicle), powertrain, None)
  }

  case class BeamVehicleIdAndRef(id: Id[Vehicle], ref: ActorRef)

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

  def buildActorName(vehicleId: Id[Vehicle]): String = {
    s"$ActorPrefixName${vehicleId.toString}"
  }

  implicit def actorRef2Id(actorRef: ActorRef): Option[Id[Vehicle]] = {
    if (actorRef.path.name.startsWith(ActorPrefixName)) {
      Some(Id.create(actorRef.path.name.substring(ActorPrefixName.length), classOf[Vehicle]))
    } else {
      None
    }
  }

  implicit def vehicleId2actorRef(vehicleId: Id[Vehicle])(implicit context: ActorContext, timeout: Timeout) = {
    context.actorSelection(s"user/${buildActorName(vehicleId)}").resolveOne(timeout.duration)
  }
  case class GetVehicleLocationEvent(time: Double) extends org.matsim.api.core.v01.events.Event(time) {
    override def getEventType: String = getClass.getName
  }

  case class AlightingConfirmation(vehicleId: Id[Vehicle])
  case class BoardingConfirmation(vehicleId: Id[Vehicle])

  case class BecomeDriver(tick: Double, driver: Id[_], passengerSchedule: Option[PassengerSchedule] = None)
  case class UnbecomeDriver(tick: Double, driver: Id[_])
  case class BecomeDriverSuccess(passengerSchedule: Option[PassengerSchedule])
  case class DriverAlreadyAssigned(vehicleId: Id[Vehicle], currentDriver: ActorRef)

  case class EnterVehicle(tick: Double, passenger : Id[Vehicle])
  case class ExitVehicle(tick: Double, passenger : Id[Vehicle])
  case class VehicleFull(vehicleId: Id[Vehicle])

  case class UpdateTrajectory(trajectory: Trajectory)
}



/**
  * Defines common behavior for any vehicle. Communicate with PersonAgent
  * VehicleManager.
  * Passenger and driver can EnterVehicle and LeaveVehicle
  */
trait BeamVehicle extends Resource with  BeamAgent[VehicleData] with TriggerShortcuts with HasServices{
  override val id: Id[Vehicle]
  def data: VehicleData

  var driver: Option[ActorRef]
  /**
    * Other vehicle that carry this one. Like ferry or track may carry a car
    */
  var carrier: Option[ActorRef] = None
  var passengers: ListBuffer[Id[Vehicle]] = ListBuffer()
  var trajectory: Option[Trajectory] = None

  var powerTrain: Powertrain

  def location(time: Double): Future[SpaceTime] = {
    trajectory match {
      case Some(traj) =>
        carrier match {
          case Some(carrierVehicle) =>
            (carrierVehicle ? GetVehicleLocationEvent(time)).mapTo[SpaceTime].recover[SpaceTime] {
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

  chainedWhen(Uninitialized){
    case Event(TriggerWithId(InitializeTrigger(tick), triggerId), _) =>
      //TODO: notify TaxiAgent with VehicleReady if this vehicle is a taxi
      goto(Idle) replying completed(triggerId)
  }

  chainedWhen(Idle) {
    case Event(BecomeDriver(tick, newDriver, newPassengerSchedule), info) =>
      if(driver.isEmpty) {
        driver = Some(beamServices.agentRefs(newDriver))
        driver.get ! BecomeDriverSuccess(newPassengerSchedule)
      }else {
        //TODO throwing an excpetion is the simplest approach b/c agents need not wait for confirmation before assuming they are drivers, but futur versions of BEAM may seek to be robust to this condition
        throw new RuntimeException(s"BeamAgent ${newDriver} attempted to become driver of vehicle ${id} but driver ${driver.get} already assigned.")
//        val beamAgent = sender()
//        beamAgent ! DriverAlreadyAssigned(id, driver.get)
      }
      stay()
    case Event(EnterVehicle(tick, newPassenger), info) =>
      val fullCapacity = data.getType.getCapacity.getSeats + data.getType.getCapacity.getStandingRoom
      if (passengers.size < fullCapacity){
        passengers += newPassenger
        driver.get ! BoardingConfirmation(newPassenger)
      } else {
        val leftSeats = fullCapacity - passengers.size
        val beamAgent = sender()
        beamAgent ! VehicleFull(id)
      }
      stay()
    case Event(UpdateTrajectory(newTrajectory), info) =>
      trajectory = Some(newTrajectory)
      stay()
  }
  chainedWhen(Moving){
    case Event(ExitVehicle(tick, passengerId), info) =>
      passengers -= passengerId
      driver.get ! AlightingConfirmation(passengerId)
      log.debug(s"Passenger ${passengerId} alighted from vehicleId=$id")
      stay()
  }

  whenUnhandled {
    case Event(GetVehicleLocationEvent(time), data) =>
      location(time) pipeTo sender()
      stay()
    case Event(request: ReservationRequest, agentInfo) =>
      driver.foreach { driverActor =>
        driverActor ! ReservationRequestWithVehicle(request, agentInfo.data)
      }
      stay()
    case Event(any, data) =>
      log.error(s"Unhandled event: $id $any $data")
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
  def push(vehicle: Id[Vehicle]) = {
    VehicleStack(vehicle +: nestedVehicles)
  }
  def outermostVehicle(): Id[Vehicle] = {
    nestedVehicles(0)
  }
  def pop(): VehicleStack = {
    VehicleStack(nestedVehicles.tail)
  }
}


