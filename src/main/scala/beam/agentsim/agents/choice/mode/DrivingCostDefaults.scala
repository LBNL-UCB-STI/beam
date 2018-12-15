package beam.agentsim.agents.choice.mode

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.router.Modes.BeamMode.CAR
import beam.router.model.EmbodiedBeamTrip
import beam.sim.BeamServices

/**
  * BEAM
  */
object DrivingCostDefaults {
  def estimateDrivingCost(
    alternatives: IndexedSeq[EmbodiedBeamTrip],
    beamServices: BeamServices
  ): IndexedSeq[Double] = {

    alternatives.map { alt =>
      alt.tripClassifier match {
        case CAR if alt.costEstimate == 0 =>
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

          // TODO: Why can this be empty?
          val maybeBeamVehicleType = beamServices.vehicleTypes.get(neededLeg.beamVehicleTypeId)
          val beamVehicleType = maybeBeamVehicleType.getOrElse(BeamVehicleType.defaultCarBeamVehicleType)

          val distance = legs.view
            .map(_.beamLeg.travelPath.distanceInM)
            .sum

          val cost =
            if (null != beamVehicleType && null != beamVehicleType.primaryFuelType && 0.0 != beamVehicleType.primaryFuelConsumptionInJoulePerMeter) {
              (distance * beamVehicleType.primaryFuelConsumptionInJoulePerMeter * beamVehicleType.primaryFuelType.priceInDollarsPerMJoule) / 1000000
            } else {
              0 //TODO
            }
          cost
        case _ =>
          0
      }
    }
  }
}
