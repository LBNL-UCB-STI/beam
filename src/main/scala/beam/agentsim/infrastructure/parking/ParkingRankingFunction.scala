package beam.agentsim.infrastructure.parking

import beam.agentsim.infrastructure.charging._

object ParkingRankingFunction {

  /**
    * computes the cost of a given parking alternative based on the stall rental and optional charging capabilities
    * @param parkingDuration duration agent will use parking stall
    * @param parkingZone the zone, which is a set of parking attributes in a TAZ with similar attributes
    * @param chargingPreferenceOption an optional charging preference from the agent, used to rank by "need"
    * @return utility of parking
    */
  def apply(parkingDuration: Double)(
    parkingZone: ParkingZone,
    chargingPreferenceOption: Option[ChargingPreference]
  ): Double = {
    val price: Double = parkingZone.pricingModel match {
      case None => 0.0
      case Some(pricingModel) =>
        pricingModel match {
          case PricingModel.FlatFee(cost, _)      => cost * 100.0
          case PricingModel.Block(cost, interval) => parkingDuration / interval * (cost * 100.0)
        }
    }

    // TODO: integrate cost of charge here
    val chargingCost: Double = parkingZone.chargingPoint match {
      case None => 0.0
      case Some(chargingPoint) =>
        val chargingPointCost: Double = ???

        // TODO: mapping from preference to VoT??
        val needAsPriceSignal: Double = chargingPreferenceOption match {
          case None                     => 0.0D
          case Some(chargingPreference) => ???
        }

        chargingPointCost + needAsPriceSignal
    }

    price + chargingCost
  }
}
