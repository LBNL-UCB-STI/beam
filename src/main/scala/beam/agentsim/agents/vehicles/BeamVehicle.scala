package beam.agentsim.agents.vehicles

import akka.actor.ActorRef
import beam.agentsim.Resource
import beam.agentsim.agents.PersonAgent
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.SeatAssignmentRule.RandomSeatAssignmentRule
import beam.agentsim.agents.vehicles.VehicleOccupancyAdministrator.DefaultVehicleOccupancyAdministrator
import beam.agentsim.agents.vehicles.VehicleProtocol._
import org.apache.log4j.Logger
import org.matsim.api.core.v01.Id
import org.matsim.utils.objectattributes.ObjectAttributes
import org.matsim.vehicles.{Vehicle, VehicleType}

import scala.util.{Random, Try}

/**
  * A [[BeamVehicle]] is a state container __administered__ by a driver ([[PersonAgent]]
  * implementing [[beam.agentsim.agents.modalBehaviors.DrivesVehicle]]). The passengers in the [[BeamVehicle]]
  * are also [[BeamVehicle]]s, however, others are possible). The
  * reference to a parent [[BeamVehicle]] is maintained in its carrier. All other information is
  * managed either through the MATSim [[Vehicle]] interface or within several other classes.
  *
  * @author saf
  * @since Beam 2.0.0
  */
// XXXX: This is a class and MUST NOT be a case class because it contains mutable state.
// If we need immutable state, we will need to operate on this through lenses.

// TODO: safety for
class BeamVehicle(val powerTrain: Powertrain,
                  val matSimVehicle: Vehicle,
                  val  initialMatsimAttributes: Option[ObjectAttributes],
                  val  beamVehicleType: BeamVehicleType,
  )
  extends Resource[BeamVehicle] {
    val log: Logger = Logger.getLogger(classOf[BeamVehicle])

  /**
    * Manages the functionality to add or remove passengers from the vehicle according
    * to standing or sitting seating occupancy information.
    */
  private val vehicleOccupancyAdministrator: VehicleOccupancyAdministrator =
    DefaultVehicleOccupancyAdministrator(this)


  /**
    * Identifier for this vehicle
    */
  val id: Id[Vehicle] = matSimVehicle.getId

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

  def getType: VehicleType = matSimVehicle.getType

  override def getId: Id[BeamVehicle] = id

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
  def becomeDriver(newDriverRef: ActorRef)
  : Either[DriverAlreadyAssigned, BecomeDriverOfVehicleSuccessAck.type ] = {
    if (driver.isEmpty) {
      driver = Option(newDriverRef)
      Right(BecomeDriverOfVehicleSuccessAck)
    } else {
      Left(DriverAlreadyAssigned(id, driver.get))
    }
  }

  /**
    * Try to remove a passenger from the vehicle. If the passenger is seated, then perhaps a standing passenger
    * will take the seat according to priorities defined through the [[SeatAssignmentRule.assignSeatOnLeave]].
    *
    * @param idToRemove the passenger [[Vehicle]] to remove.
    * @return [[Try]] expression (maybe) holding a [[ClearCarrier]] message for the driver to pass on to the passenger.
    */
  def removePassenger(idToRemove: Id[Vehicle]): Boolean = {
    vehicleOccupancyAdministrator.removePassenger(idToRemove)
  }

  /**
    * Try to add a passenger to the vehicle according to the [[SeatAssignmentRule]]
    *
    * @param idToAdd the passenger [[Vehicle]] to add
    * @return [[Either]] a message to be sent from the driver to the passenger that the vehicle
    *         capacity has been exceeded ([[Left]]) or a
    */
  def addPassenger(idToAdd: Id[Vehicle]): Boolean = {
    vehicleOccupancyAdministrator.addPassenger(idToAdd)
  }
  def canAddPassenger(): Boolean = {
    vehicleOccupancyAdministrator.canAddPassenger()
  }
}

object BeamVehicle {
  def energyPerUnitByType(vehicleTypeId: Id[VehicleType]): Double = {
    //TODO: add energy type registry
    0.0
  }

  def noSpecialChars(theString: String): String =
    theString.replaceAll("[\\\\|\\\\^]+", ":")
}

abstract class VehicleOccupancyAdministrator(val vehicle: BeamVehicle) {

  val seatedOccupancyLimit: Int = Try(vehicle.getType.getCapacity.getSeats.intValue()).getOrElse(0)
  val standingOccupancyLimit: Int = Try(vehicle.getType.getCapacity.getStandingRoom.intValue()).getOrElse(0)
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

  def addSeatedPassenger(idToAdd: Id[Vehicle]): Boolean = {
    if (seatedPassengers.size + 1 > seatedOccupancyLimit) {
      false
    } else {
      seatedPassengers += idToAdd
      true
    }
  }

  def addStandingPassenger(idToAdd: Id[Vehicle]): Boolean = {
    if (standingPassengers.size + 1 > standingOccupancyLimit) {
      false
    } else {
      standingPassengers += idToAdd
      true
    }
  }

  //TODO: Improve this API to have custom error messages
  /**
    * Try to add a passenger to the vehicle according to the [[SeatAssignmentRule]] unless standing or seating is full
    * in which case deterministically add passenger.
    *
    * @param idToAdd the passenger [[Vehicle]] to add
    * @return [[Either]] a message to be sent from the driver to the passenger that the vehicle
    *         capacity has been exceeded ([[Left]]) or a
    */
  def addPassenger(idToAdd: Id[Vehicle]): Boolean = {
    if(seatedPassengers.size==seatedOccupancyLimit){
      addStandingPassenger(idToAdd)
    }else if(standingPassengers.size==standingOccupancyLimit){
      addSeatedPassenger(idToAdd)
    }else if (seatAssignmentRule.assignSeatOnEnter(idToAdd,
      standingPassengers,
      seatedPassengers,
      vehicle.matSimVehicle)) {
      addSeatedPassenger(idToAdd)
    } else {
      addStandingPassenger(idToAdd)
    }
  }

  def canAddPassenger(): Boolean = {
    seatedPassengers.size < seatedOccupancyLimit || standingPassengers.size < standingOccupancyLimit
  }

  def removePassenger(idToRemove: Id[Vehicle]): Boolean = {
    if (seatedPassengers.contains(idToRemove)) {
      if (standingPassengers.nonEmpty) {
        seatAssignmentRule
          .assignSeatOnLeave(idToRemove,
            standingPassengers.toList,
            seatedPassengers,
            vehicle.matSimVehicle)
          .map({ idToSit =>
            standingPassengers -= idToSit
            seatedPassengers += idToSit
          })
      }
      seatedPassengers -= idToRemove
      true
    } else if(standingPassengers.contains(idToRemove)) {
      standingPassengers -= idToRemove
      true
    }else{
      false
    }
  }

  override def toString: String = s"BeamVehicle(id=${vehicle.id},driver=${vehicle.driver})"

}

object VehicleOccupancyAdministrator {

  case class DefaultVehicleOccupancyAdministrator(
                                                   override val vehicle: BeamVehicle)
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

case class VehicleStack(nestedVehicles: Vector[Id[Vehicle]] = Vector()) {
  def isEmpty = nestedVehicles.isEmpty

  def pushIfNew(vehicle: Id[Vehicle]) = {
    if (nestedVehicles.nonEmpty && nestedVehicles.head == vehicle) {
      VehicleStack(nestedVehicles)
    } else {
      VehicleStack(vehicle +: nestedVehicles)
    }
  }

  def penultimateVehicle(): Id[Vehicle] = {
    if (nestedVehicles.size < 2) throw new RuntimeException("Attempted to access penultimate vehilce when 1 or 0 are " +
      "in the vehicle stack.")
    nestedVehicles(1)
  }

  def outermostVehicle(): Id[Vehicle] = {
    nestedVehicles(0)
  }

  def pop(): VehicleStack = {
    VehicleStack(nestedVehicles.tail)
  }
}
