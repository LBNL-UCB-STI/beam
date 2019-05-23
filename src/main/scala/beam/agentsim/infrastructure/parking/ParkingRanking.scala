package beam.agentsim.infrastructure.parking

import beam.agentsim.infrastructure.charging._
import beam.agentsim.infrastructure.taz.TAZ

import org.matsim.api.core.v01.Coord

object ParkingRanking {

  type ParkingAlternative = (TAZ, ParkingType, ParkingZone, Coord)

  type RankingFunction = (ParkingZone, Double, Option[ChargingInquiry]) => Double

  val PlaceholderForChargingCosts = 0.0

  /**
    * computes the cost (= utility) of a given parking alternative based on the stall rental and optional charging capabilities
    *
    * @param parkingDuration duration agent will use parking stall
    * @param parkingZone the zone, which is a set of parking attributes in a TAZ with similar attributes
    * @param chargingInquiryOption an optional charging preference from the agent, used to rank by "need"
    * @return utility of parking
    */
  def rankingFunction(parkingDuration: Double)(
    parkingZone: ParkingZone,
    distanceToStall: Double,
    chargingInquiryOption: Option[ChargingInquiry]
  ): Double = {
    val price: Double = parkingZone.pricingModel match {
      case None => 0.0
      case Some(pricingModel) =>
        pricingModel match {
          case PricingModel.FlatFee(cost, _)      => cost * 100.0
          case PricingModel.Block(cost, interval) => parkingDuration / interval * (cost * 100.0)
        }
    }

    val chargingSpotCosts: Double = chargingInquiryOption match {
      case None =>
        0.0 // not a BEV / PHEV
      case Some(chargingData) => { // BEV / PHEV -> we use our utility function

        chargingData.utility match {
          case None => 0 // vehicle MUST charge -> we neglect the costs for charging, as we don't care
          case Some(utilityFunction) => {

            val installedCapacity = parkingZone.chargingPointType match {
              case Some(chargingPoint) => ChargingPointType.getChargingPointInstalledPowerInKw(chargingPoint)
              case None                => 0
            }

            val price = 16 // todo incorporate in chargingPointType as attribute

            val VoT = chargingData.vot

            //build alternative from data
            // - beta1 * price * installedCapacity * 1h => -$
            // - beta2 * (wd/1.4 / 3600.0 * VoT) => -$
            // - beta3 * installedCapacity
            val alternative = Map(
              "ParkingSpot"  -> Map[String, Double]("energyPriceFactor" -> 30.0, "distanceFactor" -> 50.0,  "installedCapacity" -> 100) // todo replace dummy value
            )
            utilityFunction.getUtilityOfAlternative(alternative.head._1, alternative.head._2).getOrElse(0)
          }
        }
      }
    }

    price + chargingSpotCosts
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
