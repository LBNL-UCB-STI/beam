package beam.agentsim.agents.choice.mode

import beam.router.Modes.BeamMode.CAR
import beam.router.model.{BeamLeg, EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.sim.BeamServices
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

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
      zero
    }
  }

  def estimateFuelCost(leg: EmbodiedBeamLeg, beamServices: BeamServices): Double = {
    estimateFuelCost(leg.beamLeg, leg.beamVehicleId, beamServices)
  }

  def estimateDrivingCost(
    alternatives: IndexedSeq[EmbodiedBeamTrip],
    beamServices: BeamServices
  ): IndexedSeq[Double] = {

    alternatives.map { alt =>
      if (alt.costEstimate == zero) {
        alt.legs.map(estimateFuelCost(_, beamServices)).sum
      } else {
        zero
      }
    }
  }
}
