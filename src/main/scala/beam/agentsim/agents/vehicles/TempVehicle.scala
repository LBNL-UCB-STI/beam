package beam.agentsim.agents.vehicles

import akka.actor.ActorRef
import beam.agentsim.Resource
import beam.agentsim.agents.PersonAgent
import beam.agentsim.agents.vehicles.BeamVehicle.{
  BecomeDriverSuccessAck,
  DriverAlreadyAssigned,
  SetCarrier,
  VehicleCapacityExceeded
}
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.SeatAssignmentRule.RandomSeatAssignmentRule
import beam.agentsim.agents.vehicles.VehicleOccupancyAdministrator.DefaultVehicleOccupancyAdministrator
import beam.agentsim.agents.vehicles.VehicleProtocol.ClearCarrier
import org.apache.log4j.Logger
import org.matsim.api.core.v01.Id
import org.matsim.utils.objectattributes.ObjectAttributes
import org.matsim.vehicles.{Vehicle, VehicleType}

import scala.util.{Failure, Random, Success, Try}

/**
  * A [[TempVehicle]] is a state container __administered__ by a driver ([[PersonAgent]]
  * implementing [[beam.agentsim.agents.modalBehaviors.DrivesVehicle]]). The passengers in the [[TempVehicle]]
  * are also [[TempVehicle]]s (potentially [[HumanBodyVehicle]]s, however, others are possible). The
  * reference to a parent [[TempVehicle]] is maintained in its carrier. All other information is
  * managed either through the MATSim [[Vehicle]] interface or within several other classes.
  *
  * @param manager The [[beam.agentsim.ResourceManager]] managing this [[TempVehicle]]
  * @author saf
  * @since Beam 2.0.0
  */
// XXXX: This is a class and MUST NOT be a case class because it contains mutable state.
// If we need immutable state, we will need to operate on this through lenses.
class TempVehicle(override var manager: Option[ActorRef],
                  powerTrain: Powertrain,
                  initialMatsimVehicle: Vehicle,
                  initialMatsimAttributes: Option[ObjectAttributes],
                  beamVehicleType: BeamVehicleType,
) extends Vehicle
    with Resource[TempVehicle] {
  val logger: Logger = Logger.getLogger("BeamVehicle")

  /**
    * MATSim vehicle delegate container (should be instantiated with all properties at creation).
    */
  val matSimVehicle: Vehicle = initialMatsimVehicle

  /**
    * Identifier for this vehicle
    */
  val id: Id[Vehicle] = matSimVehicle.getId

  /**
    * Vehicle power train data
    *
    * @todo This information should be partially dependent on other variables contained in VehicleType
    */
  val powertrain: Powertrain = powertrain

  /**
    * Manages the functionality to add or remove passengers from the vehicle according
    * to standing or sitting seating occupancy information.
    */
  val vehicleOccupancyAdministrator: VehicleOccupancyAdministrator =
    DefaultVehicleOccupancyAdministrator(this)

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
  var passengers: Set[Id[Vehicle]] = Set()

  override def getType: VehicleType = matSimVehicle.getType

  override def getId: Id[Vehicle] = id

  /**
    * The resource manager for this [[TempVehicle]] resource
    *
    * @param resourceManagerRef the [[beam.agentsim.ResourceManager]]
    */
  def setResourceManager(resourceManagerRef: ActorRef): Unit = {
    manager = Option(resourceManagerRef)
  }

  /**
    * Called by the driver.
    */
  def relinquishControlOfVehicle(): Unit = {
    driver = None
  }

  /**
    * Only permitted if no driver is currently set. Driver has full autonomy in vehicle, so only
    * a call of [[relinquishControlOfVehicle]] will remove the driver.
    * Send back appropriate response to caller depending on protocol.
    *
    * @param newDriverRef incoming driver
    */
  def becomeDriver(newDriverRef: ActorRef)
    : Either[DriverAlreadyAssigned, BecomeDriverSuccessAck] = {

    if (driver.isEmpty) {
      driver = Option(newDriverRef)
      Right(BecomeDriverSuccessAck(id))
    } else {
      Left(DriverAlreadyAssigned(id, driver.get))
    }
  }

  /**
  * The [[beam.agentsim.ResourceManager]] who is currently managing this vehicle. Must
  * not ever be None ([[TempVehicle]]s start out with a manager even if no driver is initially assigned.
  * There is usually only ever one manager for a vehicle.
  *
  * @todo consider adding owner as an attribute of the vehicle as well, since this is somewhat distinct
  *       from driving... (SAF 11/17)
  */
}

object TempVehicle {

  def energyPerUnitByType(vehicleTypeId: Id[VehicleType]): Double = {
    //TODO: add energy type registry
    0.0
  }

  def noSpecialChars(theString: String): String =
    theString.replaceAll("[\\\\|\\\\^]+", ":")

}

abstract class VehicleOccupancyAdministrator(val vehicle: TempVehicle) {

  val seatedOccupancyLimit: Int = vehicle.getType.getCapacity.getSeats
  val standingOccupancyLimit: Int = vehicle.getType.getCapacity.getStandingRoom
  val totalOccupancyLimit: Int = seatedOccupancyLimit + standingOccupancyLimit

  var seatedPassengers: Set[Id[Vehicle]] = Set()
  var standingPassengers: Set[Id[Vehicle]] = Set()

  implicit val seatAssignmentRule: SeatAssignmentRule

  def getSeatsRemaining: Int = seatedOccupancyLimit - seatedPassengers.size

  def getStandingRoomRemaining: Int =
    standingOccupancyLimit - standingPassengers.size

  def getTotalRoomRemaining: Int = getSeatsRemaining + getStandingRoomRemaining

  def getSeatedCrowdedness: Double =
    (seatedPassengers.size / totalOccupancyLimit).toDouble

  def getStandingCrowdedness: Double =
    (standingPassengers.size / totalOccupancyLimit).toDouble

  def getTotalCrowdedness: Double =
    ((standingPassengers.size + seatedPassengers.size) / totalOccupancyLimit).toDouble

  def addSeatedPassenger(
      idToAdd: Id[Vehicle]): Either[VehicleCapacityExceeded, SetCarrier] = {
    if (seatedPassengers.size + 1 > seatedOccupancyLimit) {
      Left(VehicleCapacityExceeded(idToAdd))
    } else {
      seatedPassengers += idToAdd
      Right(SetCarrier(idToAdd))
    }
  }

  def addStandingPassenger(
      idToAdd: Id[Vehicle]): Either[VehicleCapacityExceeded, SetCarrier] = {
    if (standingPassengers.size + 1 > standingOccupancyLimit) {
      Left(VehicleCapacityExceeded(idToAdd))
    } else {
      standingPassengers += idToAdd
      Right(SetCarrier(idToAdd))
    }
  }

  //TODO: Improve this API to have custom error messages
  /**
    * Try to add a passenger to the vehicle according to the [[SeatAssignmentRule]]
    *
    * @param idToAdd the passenger [[Vehicle]] to add
    * @return [[Either]] a message to be sent from the driver to the passenger that the vehicle
    *         capacity has been exceeded ([[Left]]) or a
    */
  def addPassenger(
      idToAdd: Id[Vehicle]): Either[VehicleCapacityExceeded, SetCarrier] = {
    if (seatAssignmentRule.assignSeatOnEnter(idToAdd,
                                             standingPassengers,
                                             seatedPassengers,
                                             vehicle)) {
      addSeatedPassenger(idToAdd)
    } else {
      addStandingPassenger(idToAdd)
    }
  }

  /**
    * Try to remove a passenger from the vehicle. If the passenger is seated, then perhaps a standing passenger
    * will take the seat according to priorities defined through the [[SeatAssignmentRule.assignSeatOnLeave]].
    *
    * @param idToRemove the passenger [[Vehicle]] to remove.
    * @return [[Try]] expression (maybe) holding a [[ClearCarrier]] message for the driver to pass on to the passenger.
    */
  def removePassenger(idToRemove: Id[Vehicle]): Try[ClearCarrier] = {
    if (seatedPassengers.contains(idToRemove)) {
      if (standingPassengers.nonEmpty) {
        seatAssignmentRule
          .assignSeatOnLeave(idToRemove,
                             standingPassengers.toList,
                             seatedPassengers,
                             vehicle)
          .map({ idToSit =>
            standingPassengers -= idToSit
            seatedPassengers += idToSit
            ClearCarrier()
          })
      } else {
        Failure(new Exception("Error"))
      }
    } else {
      standingPassengers -= idToRemove
      Success(ClearCarrier())
    }
  }

}

object VehicleOccupancyAdministrator {

  case class DefaultVehicleOccupancyAdministrator(
      override val vehicle: TempVehicle)
      extends VehicleOccupancyAdministrator(vehicle) {
    override val seatAssignmentRule: SeatAssignmentRule =
      new RandomSeatAssignmentRule()
  }

}

trait SeatAssignmentRule {
  def assignSeatOnEnter(id: Id[Vehicle],
                        standingPassengers: Set[Id[Vehicle]],
                        seatedPassengers: Set[Id[Vehicle]],
                        vehicle: Vehicle): Boolean

  def assignSeatOnLeave(id: Id[Vehicle],
                        standingPassengers: List[Id[Vehicle]],
                        seatedPassengers: Set[Id[Vehicle]],
                        vehicle: Vehicle): Try[Id[Vehicle]]
}

object SeatAssignmentRule {

  class RandomSeatAssignmentRule extends SeatAssignmentRule {
    override def assignSeatOnEnter(id: Id[Vehicle],
                                   standingPassengers: Set[Id[Vehicle]],
                                   seatedPassengers: Set[Id[Vehicle]],
                                   vehicle: Vehicle): Boolean =
      Random.nextBoolean()

    override def assignSeatOnLeave(id: Id[Vehicle],
                                   standingPassengers: List[Id[Vehicle]],
                                   seatedPassengers: Set[Id[Vehicle]],
                                   vehicle: Vehicle): Try[Id[Vehicle]] =
      Try(standingPassengers(Random.nextInt(standingPassengers.size)))
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
