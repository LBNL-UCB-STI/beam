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

  lazy val powerTrainForHumanBody: Powertrain = Powertrain.PowertrainFromMilesPerGallon(360)

  //TODO
  val defaultBicycleBeamVehicleType: BeamVehicleType = BeamVehicleType(
    "BIKE-TYPE-DEFAULT",
    0,
    0,
    0,
    null,
    0,
    0
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
      null
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
      0
    )

  val defaultCarBeamVehicleType: BeamVehicleType = BeamVehicleType(
    "CAR-TYPE-DEFAULT",
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
      case Some(Bike)      => BIKE
      case Some(RideHail)  => RIDE_HAIL
      case Some(Car)       => CAR
      case _           => NONE
    }
  }

  sealed trait FuelTypeId
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
