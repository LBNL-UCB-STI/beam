package beam.agentsim.agents.vehicles

import akka.actor.ActorRef
import beam.agentsim.agents.PersonAgent
import beam.agentsim.agents.vehicles.BeamVehicle.{SetCarrier, BecomeDriverSuccessAck, BoardingConfirmation, DriverAlreadyAssigned, VehicleFull}
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.events.AgentsimEventsBus.MatsimEvent
import beam.utils.CollectionUtils.MessageAggregate
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent
import org.matsim.vehicles.{Vehicle, VehicleCapacity, VehicleType}

import scala.collection.mutable.ListBuffer

abstract case class TempVehicle(managerRef: ActorRef) extends Vehicle {

  /**
    * Identifier for this vehicle
    */
  val id: Id[Vehicle]

  /**
    * MATSim vehicle delegate container (should be instantiated with all properties at creation).
    */
  val matSimVehicle: Vehicle

  /**
    * Vehicle power train data
    *
    * @todo This information should be partially dependent on other variables contained in VehicleType
    */
  val powertrain: Powertrain

  def getCapacity: VehicleCapacity = matSimVehicle.getType.getCapacity

  /**
    * The [[beam.agentsim.ResourceManager]] who is currently managing this vehicle. Must
    * not ever be None ([[Vehicle]]s start out with a manager even if no driver is initially assigned.
    * There is usually only ever one manager for a vehicle.
    *
    * @todo consider adding owner as an attribute of the vehicle as well, since this is somewhat distinct
    *       from driving... (SAF 11/17)
    */
  var manager: Option[ActorRef] = Option(managerRef)

  /**
    * The [[PersonAgent]] who is currently driving the vehicle (or None ==> it is idle).
    * Effectively, this is the main controller of the vehicle in space and time in the scenario environment;
    * whereas, the manager is ultimately responsible for assignment and (for now) ownership
    * of the vehicle as a physical property.
    */
  var driver: Option[ActorRef] = None

  /**
    * The vehicle that is carrying this one. Like ferry or truck may carry a car and like a car carries a human body.
    */
  var carrier: Option[ActorRef] = None

  /**
    * The list of passenger vehicles (e.g., people, AVs, cars) currently occupying the vehicle.
    */
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

  /**
    *
    * @param newPassengerVehicle
    * @return Message for driver to pass on to
    */
  def maybeAddPassenger(newPassengerVehicle: VehiclePersonId): Either[VehicleFull,SetCarrier] = {
    val fullCapacity = getType.getCapacity.getSeats + getType.getCapacity.getStandingRoom
    if (passengers.size < fullCapacity) {
      val passengerVehicleId = newPassengerVehicle.vehicleId
      passengers += passengerVehicleId
      Right(SetCarrier(passengerVehicleId))
      // TODO: Remember to make driver send
      // Assigned carrier to passenger and publish new MATSim Event

      //      beamServices.agentSimEventsBus.publish(
      //     MatsimEvent(new PersonEntersVehicleEvent(tick, newPassengerVehicle.personId, id)))
    } else {
      Left(VehicleFull(id))
    }
  }



}
  object TempVehicle {
    def energyPerUnitByType(vehicleTypeId: Id[VehicleType]): Double = {
      //TODO: add energy type registry
      0.0
    }

    def noSpecialChars(theString: String): String = theString.replaceAll("[\\\\|\\\\^]+", ":")


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
