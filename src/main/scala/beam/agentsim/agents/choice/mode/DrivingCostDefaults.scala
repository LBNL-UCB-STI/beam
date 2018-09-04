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
          val litersPerMeter =
            if (vehicle == null || vehicle.getType == null || vehicle.getType.getEngineInformation == null) {
              drivingCostConfig.defaultLitersPerMeter
            } else {
              vehicle.getType.getEngineInformation.getGasConsumption
            }
          val cost = legs.view
            .map(_.beamLeg.travelPath.distanceInM)
            .sum * litersPerMeter / LITERS_PER_GALLON * drivingCostConfig.defaultPricePerGallon // 3.78 liters per gallon and 3.115 $/gal in CA: http://www.californiagasprices.com/Prices_Nationally.aspx
          BigDecimal(cost)
        case _ =>
          BigDecimal(0)
      }
    }
  }
}
