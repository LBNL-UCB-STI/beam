package beam.agentsim.agents.choice.mode

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.FuelType.FuelType
import beam.router.model.BeamLeg

/**
  * BEAM
  */
object DrivingCost {

<<<<<<< HEAD
  def estimateDrivingCost(leg: BeamLeg, vehicleType: BeamVehicleType, fuelTypePrices: Map[FuelType, Double]): Double = {
    val distance = leg.travelPath.distanceInM
    val travelTime = leg.duration
    val consumption = vehicleType.primaryFuelConsumptionInJoulePerMeter
    val fuelPrice = fuelTypePrices(vehicleType.primaryFuelType)
    (distance * consumption * fuelPrice) / 1000000 + distance * vehicleType.monetaryCostPerMeter +
    travelTime * vehicleType.monetaryCostPerSecond + vehicleType.monetaryCostPerUsage
=======
  def estimateDrivingCost(
    distance: Double,
    travelTime: Int,
    vehicleType: BeamVehicleType,
    fuelPrice: Double
  ): Double = {
    val consumption: Double = vehicleType.primaryFuelConsumptionInJoulePerMeter
    (distance * consumption * fuelPrice) / 1000000 + distance * vehicleType.monetaryCostPerMeter + travelTime * vehicleType.monetaryCostPerSecond
>>>>>>> develop
  }

}
