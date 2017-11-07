package beam.agentsim.agents.vehicles

import akka.actor.ActorRef
import beam.agentsim.agents.PersonAgent
import beam.agentsim.agents.vehicles.BeamVehicle.{BecomeDriverSuccessAck, DriverAlreadyAssigned}
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.{Vehicle, VehicleType}

import scala.collection.mutable.ListBuffer

trait TempVehicle extends Vehicle {
  /**
    * Identifier for this vehicle
    */
  val id: Id[Vehicle]
  /**
    * The [[beam.agentsim.ResourceManager]] who is currently managing this vehicle. Must
    * not ever be None ([[Vehicle]]s start out with a manager even if no driver is initially assigned.
    */
  var manager: Option[ActorRef]

  /**
    * MATSim vehicle delegate container (should be instantiated with all properties at creation).
    */
  val matSimVehicle: Vehicle

  /**
    * The [[PersonAgent]] who is currently driving the vehicle (or None ==> it is idle).
    */
  var driver: Option[ActorRef] = None

  /**
    * Vehicle power train
    * TODO: This information should be partially dependent on other variables contained in VehicleType
    */
  val powertrain: Powertrain

  var passengers: ListBuffer[Id[Vehicle]] = ListBuffer()

  override def getType: VehicleType = matSimVehicle.getType

  override def getId: Id[Vehicle] = id

  def setManager(managerRef: ActorRef): Unit = {
    manager = Option(managerRef)
  }

  /**
    * Called by the driver.
    */
  def unsetDriver(): Unit = {
    driver = None
  }


  /**
    * Only permitted if no driver is currently set. Driver has full autonomy in vehicle, so only
    * a call of [[unsetDriver]] will remove the driver.
    * Send back appropriate response to caller depending on protocol.
    *
    * @param newDriverRef incoming driver
    */
  def setDriver(newDriverRef: ActorRef): Either[DriverAlreadyAssigned, BecomeDriverSuccessAck] = {
    if (driver.isEmpty) {
      driver = Option(newDriverRef)
      Right(BecomeDriverSuccessAck(id))
    }
    else {
      Left(DriverAlreadyAssigned(id, driver.get))
    }
  }



  object TempVehicle {
    def energyPerUnitByType(vehicleTypeId: Id[VehicleType]): Double = {
      //TODO: add energy type registry
      0.0
    }

    def noSpecialChars(theString: String): String = theString.replaceAll("[\\\\|\\\\^]+", ":")


  }

}


//
//case class VehicleStack(nestedVehicles: Vector[Id[Vehicle]] = Vector()){
//  def isEmpty = nestedVehicles.isEmpty
//
//  def pushIfNew(vehicle: Id[Vehicle]) = {
//    if (!nestedVehicles.isEmpty && nestedVehicles.head == vehicle) {
//      VehicleStack(nestedVehicles)
//    } else {
//      VehicleStack(vehicle +: nestedVehicles)
//    }
//  }
//
//  def penultimateVehicle(): Id[Vehicle] = {
//    if (nestedVehicles.size < 2) throw new RuntimeException("Attempted to access penultimate vehilce when 1 or 0 are in the vehicle stack.")
//    nestedVehicles(1)
//  }
//
//  def outermostVehicle(): Id[Vehicle] = {
//    nestedVehicles(0)
//  }
//  def pop(): VehicleStack = {
//    VehicleStack(nestedVehicles.tail)
//  }
//}
