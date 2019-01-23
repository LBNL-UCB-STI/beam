package beam.agentsim.agents.choice.mode

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.router.model.{BeamLeg, EmbodiedBeamTrip}
import beam.sim.BeamServices
import org.matsim.api.core.v01.Id

/**
  * BEAM
  */
object DrivingCost {

  def estimateDrivingCost(leg: BeamLeg, vehicleTypeId: Id[BeamVehicleType], services: BeamServices): Double = {
    val vehicleType = services.vehicleTypes.getOrElse(vehicleTypeId, BeamVehicleType.defaultCarBeamVehicleType)
    val distance = leg.travelPath.distanceInM
    val travelTime = leg.duration
    val consumption = vehicleType.primaryFuelConsumptionInJoulePerMeter
    val fuelPrice = services.fuelTypePrices(vehicleType.primaryFuelType)
    (distance * consumption * fuelPrice) / 1000000 + distance * vehicleType.monetaryCostPerMeter + travelTime * vehicleType.monetaryCostPerSecond
  }

  // TODO: Move / improve
  // This doesn't seem to belong here. The function above calculates a cost estimate for a leg (which doesn't
  // have a cost field).
  // This function here looks at embodied trips/legs, which _do_ have a cost field, and if that happens to
  // be 0, calculates something.
  def estimateDrivingCost(alternatives: IndexedSeq[EmbodiedBeamTrip], services: BeamServices): IndexedSeq[Double] = {
    alternatives.map { alt =>
      if (alt.costEstimate == 0) {
        alt.legs.map(leg => estimateDrivingCost(leg.beamLeg, leg.beamVehicleTypeId, services)).sum
      } else {
        0
      }
    }
  }
}
