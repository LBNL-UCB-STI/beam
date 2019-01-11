package beam.agentsim.agents.choice.mode

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.router.model.{BeamLeg, EmbodiedBeamTrip}
import beam.sim.BeamServices
import org.matsim.api.core.v01.Id

/**
  * BEAM
  */
object DrivingCostDefaults {
  val zero: Double = 0

  def estimateFuelCost(leg: BeamLeg, vehicleId: Id[Vehicle], beamServices: BeamServices): Double = {
    if (beamServices.vehicles != null && beamServices.vehicles.contains(vehicleId)) {
      val vehicle = beamServices.vehicles(vehicleId)
      val distance = leg.travelPath.distanceInM
      if (null != vehicle && null != vehicle.beamVehicleType && null != vehicle.beamVehicleType.primaryFuelType && 0.0 != vehicle.beamVehicleType.primaryFuelConsumptionInJoulePerMeter) {
        if (beamServices.fuelTypePrices.keySet.contains(vehicle.beamVehicleType.primaryFuelType)) {
          (distance * vehicle.beamVehicleType.primaryFuelConsumptionInJoulePerMeter * beamServices.fuelTypePrices(
            vehicle.beamVehicleType.primaryFuelType
          )) / 1000000.0
        } else {
          zero
        }
      } else {
        zero
      }
    } else {
      0 //TODO
    }
  }

  def estimateDrivingCost(
    alternatives: IndexedSeq[EmbodiedBeamTrip],
    beamServices: BeamServices
  ): IndexedSeq[Double] = {

    alternatives.map { alt =>
      if (alt.costEstimate == zero) {
        alt.legs.map(leg => estimateFuelCost(leg.beamLeg, leg.beamVehicleTypeId, beamServices)).sum
      } else {
        zero
      }
    }
  }
}
