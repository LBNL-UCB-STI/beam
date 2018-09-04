package beam.agentsim.agents.choice.mode

import beam.router.Modes.BeamMode.CAR
import beam.router.RoutingModel.EmbodiedBeamTrip
import beam.sim.BeamServices

/**
  * BEAM
  */
object DrivingCostDefaults {
  val LITERS_PER_GALLON = 3.78541

  def estimateDrivingCost(
    alternatives: Seq[EmbodiedBeamTrip],
    beamServices: BeamServices
  ): Seq[BigDecimal] = {

    val drivingCostConfig =
      beamServices.beamConfig.beam.agentsim.agents.drivingCost

    alternatives.map { alt =>
      alt.tripClassifier match {
        case CAR if alt.costEstimate == 0.0 =>
          val vehicle =
            beamServices.vehicles(alt.legs.filter(_.beamLeg.mode == CAR).head.beamVehicleId)

          val distance = alt.legs
            .map(_.beamLeg.travelPath.distanceInM)
            .sum

          val cost = if(null != vehicle && null != vehicle.beamVehicleType && null != vehicle.beamVehicleType.primaryFuelType && null != vehicle.beamVehicleType.primaryFuelConsumptionInJoule) {
            (distance * vehicle.beamVehicleType.primaryFuelConsumptionInJoule * vehicle.beamVehicleType.primaryFuelType.priceInDollarsPerMJoule) / 1000000
          } else {
            0 //TODO
          }
          BigDecimal(cost)
        case _ =>
          BigDecimal(0)
      }
    }
  }
}
