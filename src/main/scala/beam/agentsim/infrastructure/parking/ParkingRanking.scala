package beam.agentsim.infrastructure.parking

import beam.agentsim.infrastructure.charging._
import beam.agentsim.infrastructure.taz.TAZ
import scala.collection.Map

import org.matsim.api.core.v01.Coord

object ParkingRanking {

  type RankingFunction = (ParkingZone, Double, Option[ChargingPreference]) => Double

  val PlaceholderForChargingCosts = 0.0

  /**
    * computes the cost of a given parking alternative based on the stall rental and optional charging capabilities
    *
    * @param parkingDuration duration agent will use parking stall
    * @param parkingZone the zone, which is a set of parking attributes in a TAZ with similar attributes
    * @param chargingPreferenceOption an optional charging preference from the agent, used to rank by "need"
    * @return utility of parking
    */
  def rankingFunction(parkingDuration: Double)(
    parkingZone: ParkingZone,
    distanceToStall: Double,
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
    val chargingCost: Double = parkingZone.chargingPointType match {
      case None => 0.0
      case Some(chargingPointType) =>
        val chargingPointCost: Double = PlaceholderForChargingCosts

        // TODO: mapping from preference to VoT??
        val needAsPriceSignal: Double = chargingPreferenceOption match {
          case None                     => 0.0D
          case Some(chargingPreference) => PlaceholderForChargingCosts
        }

        chargingPointCost + needAsPriceSignal
    }

    price + chargingCost
  }


  /**
    * accumulator used to carry the best-ranked parking attributes along with aggregate search data
    * @param bestTAZ TAZ where best-ranked ParkingZone is stored
    * @param bestParkingType ParkingType related to the best-ranked ParkingZone
    * @param bestParkingZone the best-ranked ParkingZone
    * @param bestCoord the sampled coordinate of the stall
    * @param bestRankingValue the ranking value associated with the best-ranked ParkingZone
    */
  case class RankingAccumulator(
    bestTAZ: TAZ,
    bestParkingType: ParkingType,
    bestParkingZone: ParkingZone,
    bestCoord: Coord,
    bestRankingValue: Double
  )
}
