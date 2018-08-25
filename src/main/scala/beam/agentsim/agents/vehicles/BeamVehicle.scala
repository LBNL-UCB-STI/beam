package beam.agentsim.agents.vehicles

import akka.actor.ActorRef
import beam.agentsim.Resource
import beam.agentsim.agents.PersonAgent
import beam.agentsim.agents.vehicles.BeamVehicle.BeamVehicleState
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.VehicleProtocol._
import beam.agentsim.infrastructure.ParkingStall
import org.apache.log4j.Logger
import org.matsim.api.core.v01.Id
import org.matsim.utils.objectattributes.ObjectAttributes
import org.matsim.vehicles.{Vehicle, VehicleType}

/**
  * A [[BeamVehicle]] is a state container __administered__ by a driver ([[PersonAgent]]
  * implementing [[beam.agentsim.agents.modalbehaviors.DrivesVehicle]]). The passengers in the [[BeamVehicle]]
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
class BeamVehicle(
  val powerTrain: Powertrain,
  val matSimVehicle: Vehicle,
  val beamVehicleType: BeamVehicleType,
  var fuelLevelInJoules: Option[Double],
  val fuelCapacityInJoules: Option[Double]
) extends Resource[BeamVehicle] {
  val log: Logger = Logger.getLogger(classOf[BeamVehicle])

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

  var stall: Option[ParkingStall] = None

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
  def becomeDriver(
    newDriverRef: ActorRef
  ): Either[DriverAlreadyAssigned, BecomeDriverOfVehicleSuccessAck.type] = {
    if (driver.isEmpty) {
      driver = Option(newDriverRef)
      Right(BecomeDriverOfVehicleSuccessAck)
    } else {
      Left(DriverAlreadyAssigned(id, driver.get))
    }
  }

  def useParkingStall(newStall: ParkingStall) = {
    stall = Some(newStall)
  }

  def unsetParkingStall() = {
    stall = None
  }

  def useFuel(distanceInMeters: Double): Unit = fuelLevelInJoules foreach { fLevel =>
    fuelLevelInJoules = Some(
      fLevel - powerTrain
        .estimateConsumptionInJoules(distanceInMeters) / fuelCapacityInJoules.get
    )
  }

  def addFuel(fuelInJoules: Double): Unit = fuelLevelInJoules foreach { fLevel =>
    fuelLevelInJoules = Some(fLevel + fuelInJoules / fuelCapacityInJoules.get)
  }

  def getState(): BeamVehicleState =
    BeamVehicleState(
      fuelLevelInJoules.getOrElse(Double.NaN),
      fuelLevelInJoules.getOrElse(Double.NaN) / powerTrain.estimateConsumptionInJoules(1),
      driver,
      stall
    )

}

object BeamVehicle {

  def noSpecialChars(theString: String): String =
    theString.replaceAll("[\\\\|\\\\^]+", ":")

  case class BeamVehicleState(
    fuelLevel: Double,
    remainingRangeInM: Double,
    driver: Option[ActorRef],
    stall: Option[ParkingStall]
  )
}
