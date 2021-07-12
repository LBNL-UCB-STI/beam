package beam.agentsim.agents.vehicles

import beam.agentsim.agents.vehicles.FuelType._
import beam.agentsim.agents.vehicles.VehicleCategory._
import beam.agentsim.agents.vehicles.ChargingCapability._
import org.matsim.api.core.v01.Id

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
  sampleProbabilityWithinCategory: Double = 1.0,
  sampleProbabilityString: Option[String] = None,
  chargingCapability: Option[ChargingCapability] = None,
  payloadCapacityInKg: Option[Double] = None,
) {

  def isEV: Boolean = {
    primaryFuelType == Electricity || secondaryFuelType.contains(Electricity)
  }

  def isCaccEnabled: Boolean = {
    automationLevel >= 3
  }
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

  type FuelTypePrices = Map[FuelType, Double]
}

object VehicleCategory {
  sealed trait VehicleCategory
  case object Body extends VehicleCategory
  case object Bike extends VehicleCategory
  case object Car extends VehicleCategory
  case object MediumDutyPassenger extends VehicleCategory
  case object LightDutyTruck extends VehicleCategory
  case object HeavyDutyTruck extends VehicleCategory

  def fromString(value: String): VehicleCategory = fromStringOptional(value).get

  def fromStringOptional(value: String): Option[VehicleCategory] = {
    Vector(Body, Bike, Car, MediumDutyPassenger, LightDutyTruck, HeavyDutyTruck)
      .find(_.toString.equalsIgnoreCase(value))
  }
}

object ChargingCapability {
  sealed trait ChargingCapability
  case object XFC extends ChargingCapability
  case object DCFC extends ChargingCapability
  case object AC extends ChargingCapability

  def fromString(value: String): ChargingCapability = {
    Vector(XFC, DCFC, AC).find(_.toString.equalsIgnoreCase(value)).get
  }
}
