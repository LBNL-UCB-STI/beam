package beam.agentsim.infrastructure.parking

import beam.agentsim.agents.choice.logit.Alternative
import beam.agentsim.infrastructure.charging._
import beam.agentsim.infrastructure.taz.TAZ

object ParkingRanking {

  val PlaceholderForChargingCosts = 0.0

  /**
    * computes the cost (= utility) of a given parking alternative based on the stall rental and optional charging capabilities
    *
    * @param parkingDuration duration agent will use parking stall
    * @param parkingZone the zone, which is a set of parking attributes in a TAZ with similar attributes
    * @param chargingPreferenceOption an optional charging preference from the agent, used to rank by "need"
    * @return utility of parking
    */
  def rankingFunction(parkingDuration: Double)(
    parkingZone: ParkingZone,
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
      case None => 0.0 // not a BEV / PHEV
      case Some(chargingData) => { // BEV / PHEV -> we use our utility function

        chargingData.utility match {
          case None => 0 // vehicle MUST charge -> we neglect the costs for charging, as we don't care
          case Some(utilityFunction) => {

            val installedCapacity = parkingZone.chargingPointType match {
              case Some(chargingPoint) => ChargingPointType.getChargingPointInstalledPowerInKw(chargingPoint)
              case None                => 0
            }

            val price = 16 // todo incorporate in chargingPointType as attribute
            val walkingDistance = 1 // todo beamServices.geo.distUTMInMeters(stallLoc, inquiry.destinationUtm)
            val VoT = chargingData.vot

            //build alternative from data
            // - beta1 * price * installedCapacity * 1h => -$
            // - beta2 * (wd/1.4 / 3600.0 * VoT) => -$
            // - beta3 * installedCapacity
            val alternative = Alternative[String, String](
              parkingZone.toString,
              Map(
                "energyPriceFactor" -> (price * installedCapacity),
                "distanceFactor"    -> (walkingDistance / 1.4 / 3600.0) * VoT, // todo might be removed if we calculate the walking distance independently of charging
                "installedCapacity" -> installedCapacity
              )
            )
            utilityFunction.getUtilityOfAlternative(alternative)
          }
        }
      }
    }

    price + chargingSpotCosts
  }

  /**
    * set of common attributes for multiple stalls, used in a mapping to aggregate counts,
    * to count the presence of this set of options
    * used by [[RankingAccumulator]]
    *
    * @param parkingType parking type attribute
    * @param pricingModel optional pricing model attribute
    * @param chargingPoint optional charging point attribute
    */
  case class AvailableParkingAttributes(
    parkingType: ParkingType,
    pricingModel: Option[PricingModel],
    chargingPoint: Option[ChargingPointType]
  )

  /**
    * aggregate stall availability for a set of common attributes within a search.
    * used by [[RankingAccumulator]].
    *
    * @param available count of available stalls with the associated attributes
    * @param maxStalls count of maximum stalls with the associated attributes
    */
  case class AggregateAvailability(available: Int, maxStalls: Int) {
    def asPercentage: Double = available.toDouble / maxStalls
  }

  /**
    * a function from unique attribute combinations to the total counts of those resources found in the search
    */
  type Availability = Map[AvailableParkingAttributes, AggregateAvailability]

  /**
    * accumulator used to carry the best-ranked parking attributes along with aggregate search data
    *
    * @param bestTAZ TAZ where best-ranked ParkingZone is stored
    * @param bestParkingType ParkingType related to the best-ranked ParkingZone
    * @param bestParkingZone the best-ranked ParkingZone
    * @param bestRankingValue the ranking value associated with the best-ranked ParkingZone
    * @param availability availability of each combination of attributes found in this search
    */
  case class RankingAccumulator(
    bestTAZ: TAZ,
    bestParkingType: ParkingType,
    bestParkingZone: ParkingZone,
    bestRankingValue: Double,
    availability: Availability
  )

  /**
    * for each combination of attributes found, count the number of stalls available
    *
    * @param parkingZone parking zone to add to count
    * @param parkingType parking type attribute to add to count
    * @return this RankingAccumulator with updated availability data, used to assist the parking search heuristic
    */
  def updateAvailability(
    availability: Availability,
    parkingZone: ParkingZone,
    parkingType: ParkingType
  ): Availability = {

    // construct availability-oriented representation of this parking data
    val (thisAttributes, thisCounts) = {
      (
        AvailableParkingAttributes(parkingType, parkingZone.pricingModel, parkingZone.chargingPointType),
        AggregateAvailability(parkingZone.stallsAvailable, parkingZone.maxStalls)
      )
    }

    availability.get(thisAttributes) match {
      case None =>
        // no aggregate availability counts stored related to this set of attributes
        availability.updated(thisAttributes, thisCounts)
      case Some(agg) =>
        // add values to our aggregate of attribute availability
        val updatedCounts = agg.copy(
          available = agg.available + thisCounts.available,
          maxStalls = agg.maxStalls + thisCounts.maxStalls
        )
        availability.updated(thisAttributes, updatedCounts)
    }
  }

  /**
    * comes up with a percentage of availability of a set of parking attributes within a search
    *
    * @param availability the accumulated availability data
    * @param pricingModel the pricing model attribute counted
    * @param chargingPoint the charging point attribute counted
    * @param parkingType the parking type counted
    * @return a percentage of parking availability with these attributes found in this search
    */
  def getAvailabilityPercentage(
    availability: Availability,
    pricingModel: Option[PricingModel],
    chargingPoint: Option[ChargingPointType],
    parkingType: ParkingType
  ): Double = {

    val availabilityCategory =
      AvailableParkingAttributes(
        parkingType,
        pricingModel,
        chargingPoint
      )
    availability.get(availabilityCategory) match {
      case None      => 0.0D
      case Some(agg) => agg.available / agg.maxStalls
    }
  }
}
