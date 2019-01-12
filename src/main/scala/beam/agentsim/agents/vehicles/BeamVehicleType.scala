package beam.agentsim.agents.vehicles

import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.FuelType._
import beam.agentsim.agents.vehicles.VehicleCategory.{Bike, Body, Car, MediumDutyPassenger, Undefined, VehicleCategory}
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
                            secondaryFuelType: Option[FuelType] = None,
                            secondaryFuelConsumptionInJoulePerMeter: Option[Double] = None,
                            secondaryFuelCapacityInJoule: Option[Double] = None,
                            automationLevel: Option[String] = None,
                            maxVelocity: Option[Double] = None,
                            passengerCarUnit: Double = 1,
                            rechargeLevel2RateLimitInWatts: Option[Double] = None,
                            rechargeLevel3RateLimitInWatts: Option[Double] = None,
                            vehicleCategory: VehicleCategory = Undefined
)

object BeamVehicleType {

  // Consumption rate: https://www.brianmac.co.uk/energyexp.htm
  // 400 calories/hour == 400k J/hr @ 7km/hr or 2m/s == 55 J/m
  // Alternative: https://www.verywellfit.com/walking-calories-burned-by-miles-3887154
  // 85 calories / mile == 85k J/mi or 53 J/m
  // Assume walking a marathon is max per day
  val defaultHumanBodyBeamVehicleType: BeamVehicleType =
    BeamVehicleType(
      Id.create("BODY-TYPE-DEFAULT", classOf[BeamVehicleType]),
      0,
      0,
      0.5,
      Food,
      53,
      2.21e6,
      None,
      vehicleCategory = Body
    )

  val defaultBicycleBeamVehicleType: BeamVehicleType = BeamVehicleType(
    Id.create("BIKE-TYPE-DEFAULT", classOf[BeamVehicleType]),
    1,
    0,
    1.5,
    Food,
    defaultHumanBodyBeamVehicleType.primaryFuelConsumptionInJoulePerMeter / 5.0, // 5x more efficient than walking
    defaultHumanBodyBeamVehicleType.primaryFuelCapacityInJoule, // same capacity as human body
    None,
    vehicleCategory = Bike
  )

  val powerTrainForHumanBody: Powertrain = new Powertrain(
    BeamVehicleType.defaultHumanBodyBeamVehicleType.primaryFuelConsumptionInJoulePerMeter
  )

  // Transit default based on Diesel Bus
  val defaultTransitBeamVehicleType: BeamVehicleType =
    BeamVehicleType(
      Id.create("TRANSIT_TYPE_DEFAULT", classOf[BeamVehicleType]),
      50,
      50,
      10,
      Diesel,
      25829.7,
      30000000000.0,
      vehicleCategory = MediumDutyPassenger
    )

  val defaultCarBeamVehicleType: BeamVehicleType = BeamVehicleType(
    Id.create("CAR-TYPE-DEFAULT", classOf[BeamVehicleType]),
    4,
    0,
    4.5,
    Gasoline,
    3656.0,
    3655980000.0,
    None,
    vehicleCategory = Car
  )

  def isHumanVehicle(beamVehicleId: Id[Vehicle]): Boolean =
    beamVehicleId.toString.startsWith("body")

  def isRidehailVehicle(beamVehicleId: Id[Vehicle]): Boolean =
    beamVehicleId.toString.startsWith("rideHailVehicle")

  def isBicycleVehicle(beamVehicleId: Id[Vehicle]): Boolean =
    beamVehicleId.toString.startsWith("bike")

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
  case object Undefined extends VehicleCategory

  def fromString(value: String): VehicleCategory = {
    Vector(Body, Bike, Car, MediumDutyPassenger, LightDutyTruck, HeavyDutyTruck, Undefined)
      .find(_.toString.equalsIgnoreCase(value))
      .getOrElse(Undefined)
  }
}
