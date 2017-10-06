package beam.agentsim.agents.choice.mode

import beam.router.Modes.BeamMode.CAR
import beam.router.RoutingModel.EmbodiedBeamTrip
import beam.sim.BeamServices

/**
  * BEAM
  */
object DrivingCostDefaults {

  def estimateDrivingCost(alternatives: Vector[EmbodiedBeamTrip], beamServices: BeamServices): Vector[BigDecimal] = {
    alternatives.map{ alt =>
      alt.tripClassifier match {
        case CAR if alt.costEstimate == 0.0 =>
          val vehicle = beamServices.vehicles(alt.legs.filter(_.beamLeg.mode == CAR).head.beamVehicleId)
          val litersPerMeter = if(vehicle == null || vehicle.getType == null || vehicle.getType.getEngineInformation == null || vehicle.getType.getEngineInformation.getGasConsumption == null){
            0.0001069
          }else{
            vehicle.getType.getEngineInformation.getGasConsumption
          }
          val cost = alt.legs.map(_.beamLeg.travelPath.distanceInM).sum * litersPerMeter / 3.78541 * 3.115 // 3.78 liters per gallon and 3.115 $/gal in CA: http://www.californiagasprices.com/Prices_Nationally.aspx
          BigDecimal(cost)
        case _ =>
          BigDecimal(0)
      }
    }
  }

}
