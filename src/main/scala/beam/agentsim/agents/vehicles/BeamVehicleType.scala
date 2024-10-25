package beam.agentsim.agents.vehicles

import beam.agentsim.agents.vehicles.FuelType._
import beam.agentsim.agents.vehicles.VehicleCategory._
import beam.agentsim.infrastructure.charging.ChargingPointType
import org.matsim.api.core.v01.Id

case class BeamVehicleType(
  id: Id[BeamVehicleType],
  seatingCapacity: Int,
  standingRoomCapacity: Int,
  lengthInMeter: Double,
  curbWeightInKg: Double,
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
  sampleProbabilityWithinCategory: Double = 1.0,
  sampleProbabilityString: Option[String] = None,
  chargingCapability: Option[ChargingPointType] = None,
  payloadCapacityInKg: Option[Double] = None,
  wheelchairAccessible: Option[Boolean] = None,
  restrictRoadsByFreeSpeedInMeterPerSecond: Option[Double] = None,
  emissionsRatesFile: Option[String] = None,
  emissionsRatesInGramsPerMile: Option[VehicleEmissions.EmissionsProfile] = None
) {
  def isSharedVehicle: Boolean = id.toString.startsWith("sharedVehicle")

  def isCaccEnabled: Boolean = automationLevel >= 3

  def isConnectedAutomatedVehicle: Boolean = automationLevel >= 4

  def isWheelchairAccessible: Boolean = {
    wheelchairAccessible.getOrElse(true)
  }

  def getTotalRange: Double = {
    val primaryRange = primaryFuelCapacityInJoule / primaryFuelConsumptionInJoulePerMeter
    val secondaryRange =
      secondaryFuelCapacityInJoule.getOrElse(0.0) / secondaryFuelConsumptionInJoulePerMeter.getOrElse(1.0)
    primaryRange + secondaryRange
  }
}

object FuelType {
  sealed trait FuelType
  case object Food extends FuelType
  case object Gasoline extends FuelType
  case object Diesel extends FuelType
  case object Electricity extends FuelType
  case object Biodiesel extends FuelType
  case object Hydrogen extends FuelType
  case object NaturalGas extends FuelType
  case object Undefined extends FuelType

  def fromString(value: String): FuelType = {
    Vector(Food, Gasoline, Diesel, Electricity, Biodiesel, Hydrogen, NaturalGas, Undefined)
      .find(_.toString.equalsIgnoreCase(value))
      .getOrElse(Undefined)
  }

  type FuelTypePrices = Map[FuelType, Double]
}

object VehicleCategory {
  sealed trait VehicleCategory
  case object Body extends VehicleCategory
  case object Bike extends VehicleCategory
  case object Car extends VehicleCategory // Class 1&2a (GVWR <= 8500 lbs.)
  case object MediumDutyPassenger extends VehicleCategory
  case object Class2b3Vocational extends VehicleCategory // Class 2b&3 (GVWR 8501-14000 lbs.)
  case object Class456Vocational extends VehicleCategory // Class 4-6 (GVWR 14001-26000 lbs.)
  case object Class78Vocational extends VehicleCategory // CLass 7&8 (GVWR 26001-33,000 lbs.)
  case object Class78Tractor extends VehicleCategory // Class 7&8 Tractor (GVWR >33,000 lbs.)

  def fromString(value: String): VehicleCategory =
    try { fromStringOptional(value).get }
    catch {
      case exception: Exception => throw new RuntimeException(f"Can not parse vehicle category: '$value'.", exception)
    }

  private def fromStringOptional(value: String): Option[VehicleCategory] = {
    Vector(
      Body,
      Bike,
      Car,
      MediumDutyPassenger,
      Class2b3Vocational,
      Class456Vocational,
      Class78Vocational,
      Class78Tractor
    )
      .find(_.toString.equalsIgnoreCase(value))
  }
}
