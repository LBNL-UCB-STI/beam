package beam.agentsim.infrastructure.parking

import beam.agentsim.infrastructure.charging._
import beam.agentsim.infrastructure.taz.TAZ
import scala.collection.Map

import beam.router.BeamRouter.Location
import org.matsim.api.core.v01.Coord

object ParkingRanking {

  type ParkingAlternative = (TAZ, ParkingType, ParkingZone, Coord)

  type RankingFunction = (ParkingZone, Double, Option[ChargingInquiry]) => Double

  val PlaceholderForChargingCosts = 0.0

  /**
    * computes the cost of a given parking alternative based on the stall rental and optional charging capabilities
    * @param parkingZone the zone, which is a set of parking attributes in a TAZ with similar attributes
    * @param parkingDuration duration of the agent's activity and therefore time parking
    * @param distanceToStall walking distance to cells
    * @param valueOfTime agent's value of time
    * @return utility of parking
    */
  def apply(
    parkingZone: ParkingZone,
    parkingDuration: Double,
    distanceToStall: Double,
    valueOfTime: Double
  ): Double = {
    val price: Double = parkingZone.pricingModel match {
      case None               => 0.0
      case Some(pricingModel) => PricingModel.evaluateParkingTicket(pricingModel, parkingDuration.toInt)
    }

    // assumes 1.4 m/s walking speed, distance in meters, value of time in seconds
    val valueOfTimeSpentWalking: Double = distanceToStall / 1.4 / 3600.0 * valueOfTime


// FIXME JH
//    val chargingSpotCosts: Double = chargingInquiryOption match {
//      case None =>
//        0.0 // not a BEV / PHEV
//      case Some(chargingData) => { // BEV / PHEV -> we use our utility function
//
//        chargingData.utility match {
//          case None => 0 // vehicle MUST charge -> we neglect the costs for charging, as we don't care
//          case Some(utilityFunction) => {
//
//            val installedCapacity = parkingZone.chargingPointType match {
//              case Some(chargingPoint) => ChargingPointType.getChargingPointInstalledPowerInKw(chargingPoint)
//              case None                => 0
//            }
//
//            val price = 16 // todo incorporate in chargingPointType as attribute
//
//            val VoT = chargingData.vot
//
//            //build alternative from data
//            // - beta1 * price * installedCapacity * 1h => -$
//            // - beta2 * (wd/1.4 / 3600.0 * VoT) => -$
//            // - beta3 * installedCapacity
//            val alternative = Map(
//              "ParkingSpot" -> Map[String, Double](
//                "energyPriceFactor" -> 30.0,
//                "distanceFactor"    -> 50.0,
//                "installedCapacity" -> 100
//              ) // todo replace dummy value
//            )
//            utilityFunction.getUtilityOfAlternative(alternative.head._1, alternative.head._2).getOrElse(0)
//          }
//        }
//      }
//    }



    // TODO: include cost of charge here

    -price - valueOfTimeSpentWalking - PlaceholderForChargingCosts
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
