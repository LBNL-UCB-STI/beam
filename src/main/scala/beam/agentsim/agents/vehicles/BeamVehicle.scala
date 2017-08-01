package beam.agentsim.agents.vehicles

import akka.actor.ActorRef
import akka.pattern.{pipe, _}
import beam.agentsim.Resource
import beam.agentsim.agents.BeamAgent.{BeamAgentData, BeamAgentState, Initialized, Uninitialized}
import beam.agentsim.agents.PersonAgent.{PersonEntersVehicleTrigger, PersonLeavesVehicleTrigger}
import beam.agentsim.agents.vehicles.BeamVehicle.Traveling
import beam.agentsim.agents.{BeamAgent, InitializeTrigger, TriggerShortcuts}
import beam.agentsim.events.SpaceTime
import beam.agentsim.events.resources.vehicle.GetVehicleLocationEvent
import beam.agentsim.scheduler.BeamAgentScheduler.ScheduleTrigger
import beam.agentsim.scheduler.{Trigger, TriggerWithId}
import org.matsim.api.core.v01.Id
import org.matsim.utils.objectattributes.attributable.Attributes
import org.matsim.vehicles.{Vehicle, VehicleType}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
/**
  * @author dserdiuk
  */

abstract class Dimension

object VehicleData {
  implicit def vehicle2vehicleData(vehicle: Vehicle): VehicleData = {
    val vdata = VehicleDataImpl(vehicle.getType.getDescription,
      vehicle.getClass.getName, vehicle, new Attributes())
    vdata
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

  case object Traveling extends BeamAgentState {
    override def identifier = "Traveling"
  }

  def energyPerUnitByType(vehicleTypeId: Id[VehicleType]): Double = {
    //TODO: add energy type registry
      0.0
  }

  def buildActorName(vehicleId: Id[Vehicle], iterationName: Option[String] = None) = {
    s"vehicle-${vehicleId.toString}"
  }
}


case class EnterVehicleTrigger(tick: Double, vehicleId: Id[Vehicle], driver: Option[ActorRef] = None, passengers: Option[List[ActorRef]] = None)  extends Trigger

/**
  * when somebody leaves the vehicle
  * @param tick time when it heppens
  * @param vehicleId id of vehicle
  * @param driver driver who leaves vehicle
  * @param passengers all passengers who leave this vehicle
  */
case class LeaveVehicleTrigger(tick: Double, vehicleId: Id[Vehicle], driver: Option[ActorRef] = None, passengers: Option[List[ActorRef]] = None)  extends Trigger

case class NoEnoughSeats(tick: Double, vehicleId: Id[Vehicle], passengers: List[ActorRef], requiredSeats: Int)  extends Trigger

case class DriverAlreadyAssigned(tick: Double, vehicleId: Id[Vehicle], currentDriver: ActorRef)  extends Trigger

/**
  * Defines common behavior for any vehicle. Communicate with PersonAgent
  * VehicleManager.
  * Passenger and driver can EnterVehicle and LeaveVehicle
  */
trait BeamVehicle extends Resource with  BeamAgent[VehicleData] with TriggerShortcuts {

  def driver: Option[ActorRef]

  /**
    * Other vehicle that carry this one. Like ferry or track may carry a car
    *
    * @return
    */
  def carrier: Option[ActorRef]

  def passengers: List[ActorRef]

  def data: VehicleData

  def trajectory: Trajectory

  def powerTrain: Powertrain

  def location(time: Double): Future[SpaceTime] = {
    carrier match {
      case Some(carrierVehicle) =>
        (carrierVehicle ? GetVehicleLocationEvent(time)).mapTo[SpaceTime].recover[SpaceTime] {
          case error: Throwable =>
          log.warning(s"Failed to get location of from carrier. ", error)
          trajectory.location(time)
        }(context.dispatcher)
      case None =>
        Future.successful(trajectory.location(time))
    }
  }

  protected def setDriver(newDriver: ActorRef)

  protected def pickupPassengers(newPassengers: List[ActorRef]): Unit

  /**
    *
    * @param passengers to be dropped from vehicle
    * @return dropped passengers
    */
  protected def dropOffPassengers(passengers: List[ActorRef]) : List[ActorRef]

  chainedWhen(Uninitialized){
    case Event(TriggerWithId(InitializeTrigger(tick), triggerId), _) =>
      //TODO: notify TaxiAgent with VehicleReady if this vehicle is a taxi
      goto(Initialized) replying completed(triggerId)
  }

  chainedWhen(Initialized) {
    case Event(EnterVehicleTrigger(tick, vehicleId, newDriver, newPassengers), info) =>
      newDriver match {
        case Some(theDriver) if driver.isEmpty =>
          setDriver(theDriver)
          theDriver ! PersonEntersVehicleTrigger(tick)
        case Some(theDriver) if driver.isDefined =>
          val beamAgent = sender()
          beamAgent ! DriverAlreadyAssigned(tick, vehicleId, driver.get)
        case None if driver.isDefined =>
          log.debug(s"Keep previous driver ${driver.get.path.name} in vehicle ${data.getId}")
        case None if driver.isEmpty =>
          log.warning(s"EnterVehicle event in vehicle $vehicleId without driver ")
      }
      newPassengers match {
        case Some(theNewPassengers) =>
          val fullCapacity = data.getType.getCapacity.getSeats + data.getType.getCapacity.getStandingRoom
          val available = fullCapacity - (theNewPassengers.size + passengers.size + driver.toList.size)
          if ( available >= 0) {
            pickupPassengers(theNewPassengers)
            // send direct message to personAgent, no trigger!! + confirmation message with Ack
            theNewPassengers.foreach{
              personAgent =>
                personAgent ! PersonEntersVehicleTrigger(tick)
            }
//          val beamVehicle = self
//          // send AssignCarrier to update person's HumanVehicleBody ???
//          responseTriggers = responseTriggers ++ theNewPassengers.flatMap(personAgent => scheduleOne[AssignCarrier](tick, personAgent, beamVehicle))
          } else {
            val leftSeats = fullCapacity - passengers.size
            val beamAgent = sender()
            beamAgent ! NoEnoughSeats(tick, vehicleId, theNewPassengers, leftSeats)
          }
        case _ =>
          //do nothing
      }
      stay()
      // we should either go to traveling mode or stay() and wait special event from driver StartTrip ??
      //goto(Traveling) replying completed(triggerId, triggers)
  }
  chainedWhen(Traveling) {
    case Event(LeaveVehicleTrigger(tick, vehicleId, oldDriver, oldPassengers), info) =>
      oldPassengers match {
        case Some(passengersToDrop) =>
          val offPassengers = dropOffPassengers(passengersToDrop)
          offPassengers.foreach{ personAgent =>
            personAgent ! PersonLeavesVehicleTrigger(tick)
          }
          log.debug(s"Dropped ${offPassengers.size} passenger(s) vehicleId=$vehicleId")
        case _ =>
          log.debug(s"LeaveVehicleTrigger on tick=$tick, vehicleId=$vehicleId without passengers")
      }
      if (driver.isDefined && oldDriver.isDefined && driver.get == oldDriver.get) {
        setDriver(null)
        driver.get !  PersonLeavesVehicleTrigger(tick)
      }
      if (passengers.isEmpty) {
        //TODO: handle taxi case and notify taximanager for available taxi ???
        goto(Initialized)
      } else {
        // is it allowed to travel without driver ? auto pilot?
        stay()
      }
  }

  whenUnhandled {
    case Event(GetVehicleLocationEvent(time), data) =>
      location(time) pipeTo sender()
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


