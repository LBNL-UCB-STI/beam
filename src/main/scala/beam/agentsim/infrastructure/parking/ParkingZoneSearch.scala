package beam.agentsim.infrastructure.parking

import scala.collection.Map
import scala.util.{Failure, Success, Try}

import beam.agentsim.infrastructure.charging._
import beam.agentsim.infrastructure.taz.TAZ
import org.matsim.api.core.v01.Id

object ParkingZoneSearch {

  /**
    * a nested structure to support a search over available parking attributes,
    * where traversal either terminates in an un-defined branch (no options found),
    * or a leaf, which contains the index of a ParkingZone in the ParkingZones lookup array
    * with the matching attributes.
    */
  type StallSearch = Map[Id[TAZ], Map[ParkingType, List[Int]]]

  /**
    * find the best parking alternative for the data in this request
    * @param chargingInquiryData ChargingPreference per type of ChargingPoint
    * @param tazList the TAZ we are looking in
    * @param parkingTypes the parking types we are interested in
    * @param tree search tree of parking infrastructure
    * @param stalls stored ParkingZone data
    * @param costFunction ranking function for comparing options
    * @return the TAZ with the best ParkingZone and it's parkingZoneId
    */
  def find(
    chargingInquiryData: Option[ChargingInquiryData],
    tazList: Seq[TAZ],
    parkingTypes: Seq[ParkingType],
    tree: StallSearch,
    stalls: Array[ParkingZone],
    costFunction: (ParkingZone, Option[ChargingPreference]) => Double
  ): Option[(TAZ, ParkingType, ParkingZone, Int)] = {
    val found = findParkingZonesAndRanking(tazList, parkingTypes, tree, stalls)
    takeBestByRanking(found, chargingInquiryData, costFunction).map {
      case (taz, parkingType, parkingZone, id, _) =>
        (taz, parkingType, parkingZone, id)
    }
  }

  /**
    * look for matching ParkingZones, optionally based on charging infrastructure requirements, within a TAZ, which have vacancies
    * @param tazList the TAZ we are looking in
    * @param parkingTypes the parking types we are interested in
    * @param tree search tree of parking infrastructure
    * @param stalls stored ParkingZone data
    * @return list of discovered ParkingZone ids, corresponding to the Array[ParkingZone], ranked by the costFunction
    */
  def findParkingZonesAndRanking(
    tazList: Seq[TAZ],
    parkingTypes: Seq[ParkingType],
    tree: StallSearch,
    stalls: Array[ParkingZone]
  ): Seq[(ParkingZone, Int, TAZ, ParkingType)] = {

    // conduct search (toList required to combine Option and List monads)
    for {
      taz                 <- tazList
      parkingTypesSubtree <- tree.get(taz.tazId).toList
      parkingType         <- parkingTypes
      parkingZoneIds      <- parkingTypesSubtree.get(parkingType).toList
      parkingZoneId       <- parkingZoneIds
      if stalls(parkingZoneId).stallsAvailable > 0
    } yield {
      // get the zone
      Try {
        (stalls(parkingZoneId), parkingZoneId, taz, parkingType)
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
    * @return the best parking zone, it's id, and it's rank
    */
  def takeBestByRanking(
    found: Iterable[(ParkingZone, Int, TAZ, ParkingType)],
    chargingInquiryData: Option[ChargingInquiryData],
    costFunction: (ParkingZone, Option[ChargingPreference]) => Double
  ): Option[(TAZ, ParkingType, ParkingZone, Int, Double)] = {
    found.foldLeft(Option.empty[(TAZ, ParkingType, ParkingZone, Int, Double)]) { (bestZoneOption, parkingZoneTuple) =>
      val (thisParkingZone: ParkingZone, thisParkingZoneId: Int, thisTAZ: TAZ, thisParkingType: ParkingType) =
        parkingZoneTuple

      // rank this parking zone
      val thisRank = chargingInquiryData match {
        case None =>
          // not a charging vehicle
          costFunction(thisParkingZone, None)
        case Some(chargingData) =>
          // consider charging costs
          val pref: Option[ChargingPreference] = for {
            chargingPoint      <- thisParkingZone.chargingPoint
            chargingPreference <- chargingData.data.get(chargingPoint)
          } yield chargingPreference
          costFunction(thisParkingZone, pref)
      }

      // update fold accumulator with best-ranked parking zone along with relevant attributes
      bestZoneOption match {
        case None => Some { (thisTAZ, thisParkingType, thisParkingZone, thisParkingZoneId, thisRank) }
        case Some((_, _, _, _, bestRank)) =>
          if (bestRank < thisRank) Some { (thisTAZ, thisParkingType, thisParkingZone, thisParkingZoneId, thisRank) } else
            bestZoneOption
      }
    }
  }
}
