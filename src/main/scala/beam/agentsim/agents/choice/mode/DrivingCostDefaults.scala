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

          val joulesPerMeter: Double = ???
          val distance: Double = ???
          val litersPerMeter: Double = ??? //TODO convert fuelCapacityInJoule to litersPerMeter ?
//            if (vehicle == null || vehicle.getType == null || vehicle.getType.getEngineInformation == null) {
//              drivingCostConfig.defaultLitersPerMeter
//            } else {
//              vehicle.getType.getEngineInformation.getGasConsumption
//            }
          val cost = alt.legs
            .map(_.beamLeg.travelPath.distanceInM)
            .sum * litersPerMeter / LITERS_PER_GALLON * drivingCostConfig.defaultPricePerGallon // 3.78 liters per gallon and 3.115 $/gal in CA: http://www.californiagasprices.com/Prices_Nationally.aspx

            //TODO come up with new formula: distance * joulesPerMeter / drivingCostConfig.dolarPerJoule
          BigDecimal(cost)
        case _ =>
          BigDecimal(0)
      }
    }
  }
}
