package beam.agentsim.agents.choice.mode

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.router.model.BeamLeg
import beam.sim.BeamServices
import org.apache.commons.lang.StringUtils
import org.matsim.api.core.v01.Id

/**
  * BEAM
  */
object DrivingCost {

  def estimateDrivingCost(leg: BeamLeg, vehicleTypeId: Id[BeamVehicleType], services: BeamServices): Double = {
    if (StringUtils.isEmpty(vehicleTypeId.toString)) {
      return 0.0
    }
    val vehicleType = services.vehicleTypes(vehicleTypeId)
    val distance = leg.travelPath.distanceInM
    val travelTime = leg.duration
    val consumption = vehicleType.primaryFuelConsumptionInJoulePerMeter
    val fuelPrice = services.fuelTypePrices(vehicleType.primaryFuelType)
    (distance * consumption * fuelPrice) / 1000000 + distance * vehicleType.monetaryCostPerMeter + travelTime * vehicleType.monetaryCostPerSecond
  }

}
