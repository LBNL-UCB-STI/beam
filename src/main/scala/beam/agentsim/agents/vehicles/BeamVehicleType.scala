package beam.agentsim.agents.vehicles

import beam.agentsim.agents.vehicles.FuelType._
import beam.agentsim.agents.vehicles.VehicleCategory.{Car, VehicleCategory}
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

/**
  * Enumerates the names of recognized [[BeamVehicle]]s.
  * Useful for storing canonical naming conventions.
  *
  * @author saf
  */
case class BeamVehicleType(
  id: Id[BeamVehicleType],
  seatingCapacity: Int,
  standingRoomCapacity: Int,
  lengthInMeter: Double,
  primaryFuelType: FuelType,
  primaryFuelConsumptionInJoulePerMeter: Double,
  primaryFuelCapacityInJoule: Double,
  monetaryCostPerMeter: Double = 0.0,
  monetaryCostPerSecond: Double = 0.0,
  secondaryFuelType: Option[FuelType] = None,
  secondaryFuelConsumptionInJoulePerMeter: Option[Double] = None,
  secondaryFuelCapacityInJoule: Option[Double] = None,
  automationLevel: Int = 1,
  maxVelocity: Option[Double] = None,
  passengerCarUnit: Double = 1,
  rechargeLevel2RateLimitInWatts: Option[Double] = None,
  rechargeLevel3RateLimitInWatts: Option[Double] = None,
  vehicleCategory: VehicleCategory,
  primaryVehicleEnergyFile: Option[String] = None,
  secondaryVehicleEnergyFile: Option[String] = None,
  sampleProbabilityWithinCategory: Double = 1.0
)

object BeamVehicleType {

  def isHumanVehicle(beamVehicleId: Id[Vehicle]): Boolean =
    beamVehicleId.toString.startsWith("body")

  def isRidehailVehicle(beamVehicleId: Id[Vehicle]): Boolean =
    beamVehicleId.toString.startsWith("rideHailVehicle")

  def isBicycleVehicle(beamVehicleId: Id[Vehicle]): Boolean =
    beamVehicleId.toString.startsWith("bike")

  def isTransitVehicle(beamVehicleId: Id[Vehicle]): Boolean =
    List("bus", "train", "subway", "tram", "rail", "cable_car", "ferry")
      .exists(beamVehicleId.toString.toLowerCase.startsWith)

}

object FuelType {
  sealed trait FuelType
  case object Food extends FuelType
  case object Gasoline extends FuelType
  case object Diesel extends FuelType
  case object Electricity extends FuelType
  case object Biodiesel extends FuelType
  case object Undefined extends FuelType

  def fromString(value: String): FuelType = {
    Vector(Food, Gasoline, Diesel, Electricity, Biodiesel, Undefined)
      .find(_.toString.equalsIgnoreCase(value))
      .getOrElse(Undefined)
  }
  case class FuelTypeAndPrice(fuelTypeId: FuelType, priceInDollarsPerMJoule: Double)
}

object VehicleCategory {
  sealed trait VehicleCategory
  case object Body extends VehicleCategory
  case object Bike extends VehicleCategory
  case object Car extends VehicleCategory
  case object MediumDutyPassenger extends VehicleCategory
  case object LightDutyTruck extends VehicleCategory
  case object HeavyDutyTruck extends VehicleCategory

  def fromString(value: String): VehicleCategory = {
    Vector(Body, Bike, Car, MediumDutyPassenger, LightDutyTruck, HeavyDutyTruck)
      .find(_.toString.equalsIgnoreCase(value))
      .get
  }
}
