package beam.agentsim.infrastructure.parking

import scala.collection.Map
import scala.util.{Failure, Random, Success, Try}

import beam.agentsim.infrastructure.charging._
import beam.agentsim.infrastructure.parking.ParkingRanking.RankingAccumulator
import beam.agentsim.infrastructure.taz.TAZ
import org.matsim.api.core.v01.{Coord, Id}

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
    destinationUTM: Coord,
    chargingInquiryData: Option[ChargingInquiryData[String, String]],
    tazList: Seq[TAZ],
    parkingTypes: Seq[ParkingType],
    tree: ZoneSearch,
    parkingZones: Array[ParkingZone],
    costFunction: ParkingRanking.RankingFunction,
    distanceFunction: (Coord, Coord) => Double,
    random: Random
  ): Option[RankingAccumulator] = {
    val found = findParkingZones(destinationUTM, tazList, parkingTypes, tree, parkingZones, random)
    takeBestByRanking(destinationUTM, found, chargingInquiryData, costFunction, distanceFunction)
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
    destinationUTM: Coord,
    tazList: Seq[TAZ],
    parkingTypes: Seq[ParkingType],
    tree: ZoneSearch,
    parkingZones: Array[ParkingZone],
    random: Random
  ): Seq[(TAZ, ParkingType, ParkingZone, Coord)] = {

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
        parkingZones(parkingZoneId)
      } match {
        case Success(parkingZone) =>
          val parkingAvailability: Double = parkingZone.availability
          val stallLocation: Coord = ParkingStallSampling.availabilityAwareSampling(random, destinationUTM, taz, parkingAvailability)
          (taz, parkingType, parkingZones(parkingZoneId), stallLocation)
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
    destinationUTM: Coord,
    found: Iterable[(TAZ, ParkingType, ParkingZone, Coord)],
    chargingInquiryData: Option[ChargingInquiryData[String, String]],
    costFunction: ParkingRanking.RankingFunction,
    distanceFunction: (Coord, Coord) => Double
  ): Option[RankingAccumulator] = {
    found.foldLeft(Option.empty[RankingAccumulator]) { (accOption, parkingZoneTuple) =>

      val (thisTAZ: TAZ, thisParkingType: ParkingType, thisParkingZone: ParkingZone, stallLocation: Coord) =
        parkingZoneTuple

      val walkingDistance: Double = distanceFunction(destinationUTM, stallLocation)

      // rank this parking zone
      val thisRank = chargingInquiryData match {
        case None =>
          // not a charging vehicle
          costFunction(thisParkingZone, walkingDistance, None)
        case Some(chargingData) =>
          // consider charging costs
          val pref: Option[ChargingPreference] = for {
            chargingPoint      <- thisParkingZone.chargingPointType
            chargingPreference <- chargingData.data.get(chargingPoint)
          } yield chargingPreference
          costFunction(thisParkingZone, walkingDistance, pref)
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
              stallLocation,
              thisRank
            )
          }
        case Some(acc: RankingAccumulator) =>
          // update the aggregate data, and optionally, update the best zone if it's ranking is superior
          if (acc.bestRankingValue < thisRank) {
            Some {
              acc.copy(
                bestTAZ = thisTAZ,
                bestParkingType = thisParkingType,
                bestParkingZone = thisParkingZone,
                bestRankingValue = thisRank
              )
            }
          } else {
            // accumulator has best rank; no change
            accOption
          }
      }
    }
  }
}
