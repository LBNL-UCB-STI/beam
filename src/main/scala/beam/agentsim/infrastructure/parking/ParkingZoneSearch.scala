package beam.agentsim.infrastructure.parking

import beam.agentsim.infrastructure.ParkingStall
import beam.agentsim.infrastructure.ZonalParkingManager.DefaultParkingPrice

import scala.collection.Map
import scala.util.{Failure, Random, Success, Try}
import beam.agentsim.infrastructure.charging._
import beam.agentsim.infrastructure.parking.ParkingRanking.RankingAccumulator
import beam.agentsim.infrastructure.taz.TAZ
import beam.router.BeamRouter.Location
import beam.sim.common.GeoUtils
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.collections.QuadTree

import scala.collection.JavaConverters._
import scala.annotation.tailrec

object ParkingZoneSearch {

  /**
    * a nested structure to support a search over available parking attributes,
    * where traversal either terminates in an un-defined branch (no options found),
    * or a leaf, which contains the index of a ParkingZone in the ParkingZones lookup array
    * with the matching attributes. type parameter A is a tag from a graph partitioning, such as a TAZ,
    * or possibly an h3 label.
    */
  type ZoneSearch[A] = Map[Id[A], Map[ParkingType, List[Int]]]

  // increases search radius by this factor at each iteration
  val SearchFactor: Double = 2.0

  /**
    * looks for the nearest ParkingZone that meets the agent's needs
    * @param searchStartRadius small radius describing a ring shape
    * @param searchMaxRadius larger radius describing a ring shape
    * @param destinationUTM coordinates of this request
    * @param parkingDuration duration requested for this parking, used to calculate cost in ranking
    * @param parkingTypes types of parking this request is interested in
    * @param chargingInquiryData optional inquiry preferences for charging options
    * @param searchTree nested map structure assisting search for parking within a TAZ and by parking type
    * @param stalls collection of all parking alternatives
    * @param tazQuadTree lookup of all TAZs in this simulation
    * @param random random generator used to sample a location from the TAZ for this stall
    * @return a stall from the found ParkingZone, or a ParkingStall.DefaultStall
    */
  def incrementalParkingZoneSearch(
                                    searchStartRadius: Double,
                                    searchMaxRadius: Double,
                                    destinationUTM: Location,
                                    valueOfTime: Double,
                                    parkingDuration: Double,
                                    parkingTypes: Seq[ParkingType],
                                    chargingInquiryData: Option[ChargingInquiryData[String, String]],
                                    searchTree: ParkingZoneSearch.ZoneSearch[TAZ],
                                    stalls: Array[ParkingZone],
                                    tazQuadTree: QuadTree[TAZ],
                                    distanceFunction: (Coord, Coord) => Double,
                                    random: Random,
                                    boundingBox: Envelope
                                  ): Option[(ParkingZone, ParkingStall)] = {

    @tailrec
    def _search(thisInnerRadius: Double, thisOuterRadius: Double): Option[(ParkingZone, ParkingStall)] = {
      if (thisInnerRadius > searchMaxRadius) None
      else {
        val tazDistance: Map[TAZ, Double] =
          tazQuadTree
            .getRing(destinationUTM.getX, destinationUTM.getY, thisInnerRadius, thisOuterRadius)
            .asScala
            .map { taz =>
              (taz, GeoUtils.distFormula(taz.coord, destinationUTM))
            }
            .toMap
        val tazList: List[TAZ] = tazDistance.keys.toList

        ParkingZoneSearch.find(
          destinationUTM,
          valueOfTime,
          parkingDuration,
          chargingInquiryData,
          tazList,
          parkingTypes,
          searchTree,
          stalls,
          distanceFunction,
          random
        ) match {
          case Some(
          ParkingRanking.RankingAccumulator(
          bestTAZ,
          bestParkingType,
          bestParkingZone,
          bestCoord,
          bestRankingValue
          )
          ) =>
            val stallPrice: Double =
              bestParkingZone.pricingModel
                .map { PricingModel.evaluateParkingTicket(_, parkingDuration.toInt) }
                .getOrElse(DefaultParkingPrice)

            // create a new stall instance. you win!
            val newStall = ParkingStall(
              bestTAZ.tazId,
              bestParkingZone.parkingZoneId,
              bestCoord,
              stallPrice,
              bestParkingZone.chargingPointType,
              bestParkingZone.pricingModel,
              bestParkingType
            )

            Some { (bestParkingZone, newStall) }
          case None =>
            _search(thisOuterRadius, thisOuterRadius * SearchFactor)
        }
      }
    }

    _search(0, searchStartRadius)
  }


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
    tree: ZoneSearch[TAZ],
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
    tree: ZoneSearch[TAZ],
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
          val stallLocation: Coord =
            ParkingStallSampling.availabilityAwareSampling(random, destinationUTM, taz, parkingAvailability)
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
