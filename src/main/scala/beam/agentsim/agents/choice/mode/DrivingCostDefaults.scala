package beam.agentsim.agents.choice.mode

import beam.router.Modes.BeamMode.CAR
import beam.router.RoutingModel.EmbodiedBeamTrip
import beam.sim.BeamServices

/**
  * BEAM
  */
object DrivingCostDefaults {


  //TODO: THIS MUST BE CONFIGURABLE!!!!!
  val DEFAULT_LITERS_PER_METER = 0.0001069

  //TODO: THIS MUST BE CONFIGURABLE!!!!!
  val DEFAULT_PRICE_PER_GALLON = 3.115

  val DEFAULT_LITERS_PER_GALLON = 3.78541

  def estimateDrivingCost(alternatives: Seq[EmbodiedBeamTrip], beamServices: BeamServices): Seq[BigDecimal] = {
    alternatives.map{ alt =>
      alt.tripClassifier match {
        case CAR if alt.costEstimate == 0.0 =>
          val vehicle = beamServices.vehicles(alt.legs.filter(_.beamLeg.mode == CAR).head.beamVehicleId)
          val litersPerMeter = if(vehicle == null || vehicle.getType == null || vehicle.getType.getEngineInformation == null){
            DEFAULT_LITERS_PER_METER
          }else{
            vehicle.getType.getEngineInformation.getGasConsumption
          }
          val cost = alt.legs.map(_.beamLeg.travelPath.distanceInM).sum * litersPerMeter / DEFAULT_LITERS_PER_GALLON * DEFAULT_PRICE_PER_GALLON // 3.78 liters per gallon and 3.115 $/gal in CA: http://www.californiagasprices.com/Prices_Nationally.aspx
          BigDecimal(cost)
        case _ =>
          BigDecimal(0)
      }
    }
  }

}
