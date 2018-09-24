package beam.agentsim.agents.vehicles

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
  primaryFuelConsumptionInJoule: Double,
  primaryFuelCapacityInJoule: Double,
  secondaryFuelType: FuelType,
  secondaryFuelConsumptionInJoule: Double,
  secondaryFuelCapacityInJoule: Double,
  automationLevel: String,
  maxVelocity: Double,
  passengerCarUnit: String,
  rechargeLevel2RateLimitInWatts: Double,
  rechargeLevel3RateLimitInWatts: Double,
  vehicleCategory: String
) {

  def getCost(distance: Double): Double = {
    primaryFuelType.priceInDollarsPerMJoule * primaryFuelConsumptionInJoule * distance
  }
}

object BeamVehicleType {

  val defaultBicycleBeamVehicleType: BeamVehicleType = BeamVehicleType(
    "BIKE-TYPE-DEFAULT",
    0,
    0,
    0,
    null,
    0,
    0,
    null,
    0,
    0,
    null,
    0,
    null,
    0,
    0,
    "bicycle"
  )

  // Consumption rate: https://www.brianmac.co.uk/energyexp.htm
  // 400 calories/hour == 1255 J/hr @ 7km/hr or 2m/s == 0.17928571428 J/m
  // Assume walking a marathon is max per day
  val defaultHumanBodyBeamVehicleType: BeamVehicleType =
    BeamVehicleType(
      "BODY-TYPE-DEFAULT",
      0,
      0,
      0.5,
      null,
      0.17928571428,
      7500,
      null,
      0,
      0,
      null,
      0,
      null,
      0,
      0,
      "Human"
    )

  //TODO
  val defaultTransitBeamVehicleType: BeamVehicleType =
    BeamVehicleType(
      "TRANSIT-TYPE-DEFAULT",
      0,
      0,
      0,
      null,
      0,
      0,
      null,
      0,
      0,
      null,
      0,
      null,
      0,
      0,
      "TRANSIT"
    )

  val defaultCarBeamVehicleType: BeamVehicleType = BeamVehicleType(
    "CAR-TYPE-DEFAULT",
    4,
    0,
    4.5,
    new FuelType("gasoline", 0.0),
    3656.0,
    3655980000.0,
    null,
    0,
    0,
    null,
    60.0,
    null,
    0,
    0,
    "CAR"
  )

  def isHumanVehicle(beamVehicleId: Id[Vehicle]): Boolean =
    beamVehicleId.toString.startsWith("body")

  def isRidehailVehicle(beamVehicleId: Id[Vehicle]): Boolean =
    beamVehicleId.toString.startsWith("rideHailVehicle")

  def isBicycleVehicle(beamVehicleId: Id[Vehicle]): Boolean =
    beamVehicleId.toString.startsWith("bike")

  lazy val powerTrainForHumanBody: Powertrain = Powertrain.PowertrainFromMilesPerGallon(360)

  def getMode(beamVehicle: BeamVehicle): BeamMode = {
    beamVehicle.beamVehicleType.vehicleCategory match {
      //TODO complete list
      case "BIKE"      => BIKE
      case "RIDE_HAIL" => RIDE_HAIL
      case "CAR"       => CAR
      case "CAR"       => CAR
      case _           => NONE
    }
  }
}

case class FuelType(fuelTypeId: String, priceInDollarsPerMJoule: Double)
