package beam.agentsim.agents.vehicles

import beam.agentsim.agents.vehicles.BeamVehicleType.{FuelTypeId, VehicleCategory}
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{BIKE, CAR, NONE, RIDE_HAIL}
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

/**
  * Enumerates the names of recognized [[BeamVehicle]]s.
  * Useful for storing canonical naming conventions.
  *
  * @author saf
  */
case class BeamVehicleType(
  vehicleTypeId: String,
  seatingCapacity: Double,
  standingRoomCapacity: Double,
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
  vehicleCategory: Option[VehicleCategory] = None
) {

  def getCost(distance: Double): Double = {
    primaryFuelType.priceInDollarsPerMJoule * primaryFuelConsumptionInJoulePerMeter * distance
  }
}

object BeamVehicleType {

  val BODY_TYPE_DEFAULT = "BODY-TYPE-DEFAULT"
  val BIKE_TYPE_DEFAULT = "BIKE-TYPE-DEFAULT"
  val TRANSIT_TYPE_DEFAULT = "TRANSIT-TYPE-DEFAULT"
  val CAR_TYPE_DEFAULT = "CAR-TYPE-DEFAULT"

  //TODO
  val defaultBicycleBeamVehicleType: BeamVehicleType = BeamVehicleType(
    BIKE_TYPE_DEFAULT,
    0,
    0,
    0,
    null,
    0,
    0
  )

  // Consumption rate: https://www.brianmac.co.uk/energyexp.htm
  // 400 calories/hour == 400k J/hr @ 7km/hr or 2m/s == 55 J/m
  // Alternative: https://www.verywellfit.com/walking-calories-burned-by-miles-3887154
  // 85 calories / mile == 85k J/mi or 53 J/m
  // Assume walking a marathon is max per day
  val defaultHumanBodyBeamVehicleType: BeamVehicleType =
    BeamVehicleType(
      BODY_TYPE_DEFAULT,
      0,
      0,
      0.5,
      new FuelType(Food, 0.0),
      53,
      2.21e6,
      null
    )

  val powerTrainForHumanBody: Powertrain = new Powertrain(
    BeamVehicleType.defaultHumanBodyBeamVehicleType.primaryFuelConsumptionInJoulePerMeter
  )

  //TODO
  val defaultTransitBeamVehicleType: BeamVehicleType =
    BeamVehicleType(
      TRANSIT_TYPE_DEFAULT,
      0,
      0,
      0,
      null,
      0,
      0
    )

  val defaultCarBeamVehicleType: BeamVehicleType = BeamVehicleType(
    CAR_TYPE_DEFAULT,
    4,
    0,
    4.5,
    new FuelType(Gasoline, 0.0),
    3656.0,
    3655980000.0,
    null
  )

  def isHumanVehicle(beamVehicleId: Id[Vehicle]): Boolean =
    beamVehicleId.toString.startsWith("body")

  def isRidehailVehicle(beamVehicleId: Id[Vehicle]): Boolean =
    beamVehicleId.toString.startsWith("rideHailVehicle")

  def isBicycleVehicle(beamVehicleId: Id[Vehicle]): Boolean =
    beamVehicleId.toString.startsWith("bike")

  def getMode(beamVehicle: BeamVehicle): BeamMode = {
    beamVehicle.beamVehicleType.vehicleCategory match {
      //TODO complete list
      case Some(Bike)     => BIKE
      case Some(RideHail) => RIDE_HAIL
      case Some(Car)      => CAR
      case _              => NONE
    }

//  def getMode(beamVehicle: BeamVehicle): BeamMode = {
//    beamVehicle.beamVehicleType.vehicleTypeId match {
//      //TODO complete list
//      case vid if vid.toLowerCase.contains("bike")     => BIKE
//      case vid if vid.toLowerCase.contains("ridehail") => RIDE_HAIL
//      case vid if vid.toLowerCase.contains("car")      => CAR
//
//    }
//
//    beamVehicle.beamVehicleType.vehicleCategory match {
//      //TODO complete list
//      case Some(Bike)      => BIKE
//      case Some(RideHail)  => RIDE_HAIL
//      case Some(Car)       => CAR
//      case _           => NONE
//    }

  }

  sealed trait FuelTypeId
  case object Food extends FuelTypeId
  case object Gasoline extends FuelTypeId
  case object Diesel extends FuelTypeId
  case object Electricity extends FuelTypeId
  case object Biodiesel extends FuelTypeId

  sealed trait VehicleCategory
  case object Car extends VehicleCategory
  case object Bike extends VehicleCategory
  case object RideHail extends VehicleCategory
}

case class FuelType(fuelTypeId: FuelTypeId, priceInDollarsPerMJoule: Double)
