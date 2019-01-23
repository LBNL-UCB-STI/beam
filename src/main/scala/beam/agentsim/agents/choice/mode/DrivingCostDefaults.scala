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

  def estimateFuelCost(leg: BeamLeg, vehicleTypeId: Id[BeamVehicleType], beamServices: BeamServices): Double = {
    val maybeBeamVehicleType = beamServices.vehicleTypes.get(vehicleTypeId)
    val beamVehicleType = maybeBeamVehicleType.getOrElse(BeamVehicleType.defaultCarBeamVehicleType)
    val distance = leg.travelPath.distanceInM
    if (null != beamVehicleType && null != beamVehicleType.primaryFuelType && 0.0 != beamVehicleType.primaryFuelConsumptionInJoulePerMeter) {
      (distance * beamVehicleType.primaryFuelConsumptionInJoulePerMeter * beamServices.fuelTypePrices(
        beamVehicleType.primaryFuelType
      )) / 1000000
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
