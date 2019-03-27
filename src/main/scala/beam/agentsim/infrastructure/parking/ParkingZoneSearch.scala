package beam.agentsim.infrastructure.parking

import scala.collection.Map
import scala.util.{Failure, Success, Try}
import beam.agentsim.infrastructure.charging._
import beam.agentsim.infrastructure.parking.ParkingRanking.RankingAccumulator
import beam.agentsim.infrastructure.taz.TAZ
import org.matsim.api.core.v01.Id

object ParkingZoneSearch {

  /**
    * a nested structure to support a search over available parking attributes,
    * where traversal either terminates in an un-defined branch (no options found),
    * or a leaf, which contains the index of a ParkingZone in the ParkingZones lookup array
    * with the matching attributes.
    */
  type ZoneSearch = Map[Id[TAZ], Map[ParkingType, List[Int]]]

  /**
    * find the best parking alternative for the data in this request
    * @param chargingInquiryData ChargingPreference per type of ChargingPoint
    * @param tazList the TAZ we are looking in
    * @param parkingTypes the parking types we are interested in
    * @param tree search tree of parking infrastructure
    * @param parkingZones stored ParkingZone data
    * @param costFunction ranking function for comparing options
    * @return the TAZ with the best ParkingZone, it's ParkingType, and the ranking value of that ParkingZone
    */
  def find(
    chargingInquiryData: Option[ChargingInquiryData[String, String]],
    tazList: Seq[TAZ],
    parkingTypes: Seq[ParkingType],
    tree: ZoneSearch,
    parkingZones: Array[ParkingZone],
    costFunction: (ParkingZone, Option[ChargingPreference]) => Double
  ): Option[RankingAccumulator] = {
    val found = findParkingZones(tazList, parkingTypes, tree, parkingZones)
    takeBestByRanking(found, chargingInquiryData, costFunction)
  }

  /**
    * look for matching ParkingZones, within a TAZ, which have vacancies
    * @param tazList the TAZ we are looking in
    * @param parkingTypes the parking types we are interested in
    * @param tree search tree of parking infrastructure
    * @param parkingZones stored ParkingZone data
    * @return list of discovered ParkingZones
    */
  def findParkingZones(
    tazList: Seq[TAZ],
    parkingTypes: Seq[ParkingType],
    tree: ZoneSearch,
    parkingZones: Array[ParkingZone]
  ): Seq[(TAZ, ParkingType, ParkingZone)] = {

    // conduct search (toList required to combine Option and List monads)
    for {
      taz                 <- tazList
      parkingTypesSubtree <- tree.get(taz.tazId).toList
      parkingType         <- parkingTypes
      parkingZoneIds      <- parkingTypesSubtree.get(parkingType).toList
      parkingZoneId       <- parkingZoneIds
      if parkingZones(parkingZoneId).stallsAvailable > 0
    } yield {
      // get the zone
      Try {
        (taz, parkingType, parkingZones(parkingZoneId))
      } match {
        case Success(zone) => zone
        case Failure(e) =>
          throw new IndexOutOfBoundsException(s"Attempting to access ParkingZone with index $parkingZoneId failed.\n$e")
      }
    }
  }

  /**
    * finds the best parking zone id based on maximizing it's associated cost function evaluation
    * @param found the ranked parkingZones
    * @param costFunction ranking function for comparing options
    * @param chargingInquiryData ChargingPreference per type of ChargingPoint
    * @return the best parking zone, it's TAZ, ParkingType, and ranking evaluation
    */
  def takeBestByRanking(
    found: Iterable[(TAZ, ParkingType, ParkingZone)],
    chargingInquiryData: Option[ChargingInquiryData[String, String]],
    costFunction: (ParkingZone, Option[ChargingPreference]) => Double
  ): Option[RankingAccumulator] = {
    found.foldLeft(Option.empty[RankingAccumulator]) { (accOption, parkingZoneTuple) =>
      val (thisTAZ: TAZ, thisParkingType: ParkingType, thisParkingZone: ParkingZone) =
        parkingZoneTuple

      // rank this parking zone
      val thisRank = chargingInquiryData match {
        case None =>
          // not a charging vehicle
          costFunction(thisParkingZone, None)
        case Some(chargingData) =>
          // consider charging costs
          val pref: Option[ChargingPreference] = for {
            chargingPoint      <- thisParkingZone.chargingPointType
            chargingPreference <- chargingData.data.get(chargingPoint)
          } yield chargingPreference
          costFunction(thisParkingZone, pref)
      }

      // add aggregate data to this accumulator
      val updatedAvailability: ParkingRanking.Availability =
        accOption match {
          case None =>
            ParkingRanking.updateAvailability(Map.empty, thisParkingZone, thisParkingType)
          case Some(accumulator) =>
            ParkingRanking.updateAvailability(accumulator.availability, thisParkingZone, thisParkingType)
        }

      // update fold accumulator with best-ranked parking zone along with relevant attributes
      accOption match {
        case None =>
          // the first zone found becomes the first accumulator
          Some {
            RankingAccumulator(
              thisTAZ,
              thisParkingType,
              thisParkingZone,
              thisRank,
              updatedAvailability
            )
          }
        case Some(acc: RankingAccumulator) =>
          // update the aggregate data, and optionally, update the best zone if it's ranking is superior
          if (acc.bestRankingValue < thisRank)
            Some {
              acc.copy(
                bestTAZ = thisTAZ,
                bestParkingType = thisParkingType,
                bestParkingZone = thisParkingZone,
                bestRankingValue = thisRank,
                availability = updatedAvailability
              )
            } else
            Some {
              acc.copy(
                availability = updatedAvailability
              )
            }
      }
    }
  }
}
