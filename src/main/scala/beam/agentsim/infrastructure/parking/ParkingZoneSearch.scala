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
    * @param destinationUTM coordinates of this request
    * @param valueOfTime agent's value of time in seconds
    * @param chargingInquiryData ChargingPreference per type of ChargingPoint
    * @param tazList the TAZ we are looking in
    * @param parkingTypes the parking types we are interested in
    * @param tree search tree of parking infrastructure
    * @param parkingZones stored ParkingZone data
    * @param distanceFunction a function that computes the distance between two coordinates
    * @param random random generator
    * @return the TAZ with the best ParkingZone, it's ParkingType, and the ranking value of that ParkingZone
    */
  def find(
    destinationUTM: Coord,
    valueOfTime: Double,
    parkingDuration: Double,
    chargingInquiryData: Option[ChargingInquiryData[String, String]],
    tazList: Seq[TAZ],
    parkingTypes: Seq[ParkingType],
    tree: ZoneSearch,
    parkingZones: Array[ParkingZone],
    distanceFunction: (Coord, Coord) => Double,
    random: Random
  ): Option[RankingAccumulator] = {
    val found = findParkingZones(destinationUTM, tazList, parkingTypes, tree, parkingZones, random)
    takeBestByRanking(destinationUTM, valueOfTime, parkingDuration, found, chargingInquiryData, distanceFunction)
  }

  /**
    * look for matching ParkingZones, within a TAZ, which have vacancies
    * @param destinationUTM coordinates of this request
    * @param tazList the TAZ we are looking in
    * @param parkingTypes the parking types we are interested in
    * @param tree search tree of parking infrastructure
    * @param parkingZones stored ParkingZone data
    * @param random random generator
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
    * @param destinationUTM coordinates of this request
    * @param valueOfTime agent's value of time in seconds
    * @param found the ranked parkingZones
    * @param chargingInquiryData ChargingPreference per type of ChargingPoint
    * @param distanceFunction a function that computes the distance between two coordinates
    * @return the best parking option based on our cost function ranking evaluation
    */
  def takeBestByRanking(
    destinationUTM: Coord,
    valueOfTime: Double,
    parkingDuration: Double,
    found: Iterable[(TAZ, ParkingType, ParkingZone, Coord)],
    chargingInquiryData: Option[ChargingInquiryData[String, String]],
    distanceFunction: (Coord, Coord) => Double
  ): Option[RankingAccumulator] = {
    found.foldLeft(Option.empty[RankingAccumulator]) { (accOption, parkingZoneTuple) =>

      val (thisTAZ: TAZ, thisParkingType: ParkingType, thisParkingZone: ParkingZone, stallLocation: Coord) =
        parkingZoneTuple

      val walkingDistance: Double = distanceFunction(destinationUTM, stallLocation)

      // rank this parking zone
      val thisRank = ParkingRanking(thisParkingZone, parkingDuration, walkingDistance, valueOfTime)

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
                bestCoord = stallLocation,
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
