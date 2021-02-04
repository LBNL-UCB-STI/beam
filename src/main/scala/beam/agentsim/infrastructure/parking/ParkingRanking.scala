package beam.agentsim.infrastructure.parking

import beam.agentsim.infrastructure.charging._
import beam.agentsim.infrastructure.taz.TAZ
import scala.collection.Map

import beam.router.BeamRouter.Location
import org.matsim.api.core.v01.Coord

object ParkingRanking {

  type RankingFunction[GEO] = (ParkingZone[GEO], Double, Option[ChargingInquiry[GEO]]) => Double

  val PlaceholderForChargingCosts = 0.0

  /**
    * computes the cost of a given parking alternative based on the stall rental and optional charging capabilities
    * @param parkingZone the zone, which is a set of parking attributes in a TAZ with similar attributes
    * @param parkingDuration duration of the agent's activity and therefore time parking
    * @param distanceToStall walking distance to cells
    * @param valueOfTime agent's value of time
    * @return utility of parking
    */
  def rankingValue[GEO](
    parkingZone: ParkingZone[GEO],
    parkingDuration: Double,
    distanceToStall: Double,
    valueOfTime: Double,
    chargingInquiry: Option[ChargingInquiry[GEO]]
  ): Double = {
    val parkingTicket: Double = parkingZone.pricingModel match {
      case None               => 0.0
      case Some(pricingModel) => PricingModel.evaluateParkingTicket(pricingModel, parkingDuration.toInt)
    }

    // assumes 1.4 m/s walking speed, distance in meters, value of time in seconds
    val valueOfTimeSpentWalking: Double = distanceToStall / 1.4 / 3600.0 * valueOfTime

    // is either a cost we count against this alternative, or, zero, if we are non-electric or have a need for charging
    val installedCapacityTerm: Double = chargingInquiry match {
      case None =>
        0.0 // not a BEV / PHEV
      case Some(chargingData) => { // BEV / PHEV -> we use our utility function
        chargingData.utility match {
          case None => 0.0 // vehicle MUST charge -> we neglect the costs for charging, as we don't care
          case Some(_) =>
            parkingZone.chargingPointType match {
              case Some(chargingPoint) => ChargingPointType.getChargingPointInstalledPowerInKw(chargingPoint)
              case None                => 0
            }
        }
      }
    }

//    utilityFunction.getUtilityOfAlternative(alternative.head._1, alternative.head._2).getOrElse(0)
//
//    parkingAlternative ->
//      Map(
//        "energyPriceFactor" -> (parkingTicket * installedCapacityTerm),
//        "distanceFactor"    -> (distanceToStall / 1.4 / 3600.0) * valueOfTime,
//        "installedCapacity" -> installedCapacityTerm
//      )
//

//    we need to get the utility of this alternative. we want to get this value regardless of our
//    need for charging. this implies that UtilityFunction should not be optional for a
//    parking inquiry.

    ???
  }
}
