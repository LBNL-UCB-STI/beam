package beam.agentsim.agents.choice.mode

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.FuelType.FuelType
import beam.router.model.BeamLeg

/**
  * BEAM
  */
object DrivingCost {

  def estimateDrivingCost(
    distance: Double,
    travelTime: Int,
    vehicleType: BeamVehicleType,
    fuelPrice: Double
  ): Double = {
    val consumption: Double = vehicleType.primaryFuelConsumptionInJoulePerMeter
    (distance * consumption * fuelPrice) / 1000000 + distance * vehicleType.monetaryCostPerMeter + travelTime * vehicleType.monetaryCostPerSecond
  }

}
