package beam.agentsim.agents.choice.mode

import beam.router.Modes.BeamMode.CAR
import beam.router.model.EmbodiedBeamTrip
import beam.sim.BeamServices

/**
  * BEAM
  */
object DrivingCostDefaults {
  val LITERS_PER_GALLON = 3.78541

  def estimateDrivingCost(
    alternatives: IndexedSeq[EmbodiedBeamTrip],
    beamServices: BeamServices
  ): IndexedSeq[BigDecimal] = {

    val drivingCostConfig =
      beamServices.beamConfig.beam.agentsim.agents.drivingCost

    alternatives.map { alt =>
      alt.tripClassifier match {
        case CAR if alt.costEstimate == 0.0 =>
          val legs = alt.legs
          val neededLeg = legs
            .collectFirst {
              case leg if leg.beamLeg.mode == CAR => leg
            }
            .getOrElse(
              throw new RuntimeException(
                "Could not find EmbodiedBeamLeg when leg.beamLeg.mode == CAR"
              )
            )

          val vehicle = beamServices.vehicles(neededLeg.beamVehicleId)

          val distance = legs.view
            .map(_.beamLeg.travelPath.distanceInM)
            .sum

          val cost =
            if (null != vehicle && null != vehicle.beamVehicleType && null != vehicle.beamVehicleType.primaryFuelType && null != vehicle.beamVehicleType.primaryFuelConsumptionInJoule) {
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
