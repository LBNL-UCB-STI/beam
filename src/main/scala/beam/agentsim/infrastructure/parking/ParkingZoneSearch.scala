package beam.agentsim.infrastructure.parking

import beam.agentsim.agents.choice.logit.MultinomialLogit
import scala.util.{Failure, Random, Success, Try}
import beam.agentsim.infrastructure.charging._
import beam.agentsim.infrastructure.taz.TAZ
import org.matsim.api.core.v01.{Coord, Id}

object ParkingZoneSearch {

  /**
    * a nested structure to support a search over available parking attributes,
    * where traversal either terminates in an un-defined branch (no options found),
    * or a leaf, which contains the index of a ParkingZone in the ParkingZones lookup array
    * with the matching attributes. type parameter A is a tag from a graph partitioning, such as a TAZ,
    * or possibly an h3 label.
    */
  type ZoneSearch[A] = Map[Id[A], Map[ParkingType, List[Int]]]

  /**
    * these are the alternatives that are generated/instantiated by a search
    * and then are selected by either a sampling function (via a multinomial
    * logit function) or by ranking the utility of these alternatives.
    */
  case class ParkingAlternative(taz: TAZ, parkingType: ParkingType, parkingZone: ParkingZone, coord: Coord)

  /**
    * the best-ranked parking attributes along with aggregate search data
    *
    * @param bestTAZ         TAZ where best-ranked ParkingZone is stored
    * @param bestParkingType ParkingType related to the best-ranked ParkingZone
    * @param bestParkingZone the best-ranked ParkingZone
    * @param bestCoord       the sampled coordinate of the stall
    * @param bestUtility     the ranking value/utility score associated with the selected ParkingZone
    */
  case class ParkingSearchResult(
    bestTAZ: TAZ,
    bestParkingType: ParkingType,
    bestParkingZone: ParkingZone,
    bestCoord: Coord,
    bestUtility: Double
  )

  /**
    * find the best parking alternative for the data in this request
    *
    * @param destinationUTM   coordinates of this request
    * @param valueOfTime      agent's value of time in seconds
    * @param utilityFunction  a utility function for parking alternatives
    * @param tazList          the TAZ we are looking in
    * @param parkingTypes     the parking types we are interested in
    * @param tree             search tree of parking infrastructure
    * @param parkingZones     stored ParkingZone data
    * @param distanceFunction a function that computes the distance between two coordinates
    * @param random           random generator
    * @return the TAZ with the best ParkingZone, it's ParkingType, and the ranking value of that ParkingZone
    */
  def find(
    destinationUTM: Coord,
    valueOfTime: Double,
    parkingDuration: Double,
    utilityFunction: MultinomialLogit[ParkingZoneSearch.ParkingAlternative, String],
    tazList: Seq[TAZ],
    parkingTypes: Seq[ParkingType],
    tree: ZoneSearch[TAZ],
    parkingZones: Array[ParkingZone],
    distanceFunction: (Coord, Coord) => Double,
    random: Random,
    returnSpotsWithChargers: Boolean,
    returnSpotsWithoutChargers: Boolean
  ): Option[ParkingSearchResult] = {
    val found = findParkingZones(
      destinationUTM,
      tazList,
      parkingTypes,
      tree,
      parkingZones,
      random,
      returnSpotsWithChargers,
      returnSpotsWithoutChargers
    )
    takeBestBySampling(
      found,
      destinationUTM,
      parkingDuration.toInt,
      valueOfTime,
      utilityFunction,
      distanceFunction,
      random
    )
  }

  /**
    * look for matching ParkingZones, within a TAZ, which have vacancies
    *
    * @param destinationUTM coordinates of this request
    * @param tazList        the TAZ we are looking in
    * @param parkingTypes   the parking types we are interested in
    * @param tree           search tree of parking infrastructure
    * @param parkingZones   stored ParkingZone data
    * @param random         random generator
    * @return list of discovered ParkingZones
    */
  def findParkingZones(
    destinationUTM: Coord,
    tazList: Seq[TAZ],
    parkingTypes: Seq[ParkingType],
    tree: ZoneSearch[TAZ],
    parkingZones: Array[ParkingZone],
    random: Random,
    returnSpotsWithChargers: Boolean,
    returnSpotsWithoutChargers: Boolean
  ): Seq[ParkingAlternative] = {

    // conduct search (toList required to combine Option and List monads)
    for {
      taz                 <- tazList
      parkingTypesSubtree <- tree.get(taz.tazId).toList
      parkingType         <- parkingTypes
      parkingZoneIds      <- parkingTypesSubtree.get(parkingType).toList
      parkingZoneId       <- parkingZoneIds
      if parkingZones(parkingZoneId).stallsAvailable > 0 && canThisCarParkHere(
        parkingZones(parkingZoneId),
        parkingType,
        returnSpotsWithChargers,
        returnSpotsWithoutChargers
      )
    } yield {
      // get the zone
      Try {
        parkingZones(parkingZoneId)
      } match {
        case Success(parkingZone) =>
          val parkingAvailability: Double = parkingZone.availability
          val stallLocation: Coord =
            ParkingStallSampling.availabilityAwareSampling(random, destinationUTM, taz, parkingAvailability)
          ParkingAlternative(taz, parkingType, parkingZones(parkingZoneId), stallLocation)
        case Failure(e) =>
          throw new IndexOutOfBoundsException(s"Attempting to access ParkingZone with index $parkingZoneId failed.\n$e")
      }
    }
  }

  def canThisCarParkHere(
    parkingZone: ParkingZone,
    parkingType: ParkingType,
    returnSpotsWithChargers: Boolean,
    returnSpotsWithoutChargers: Boolean
  ): Boolean = {
    parkingZone.chargingPointType match {
      case Some(_) => returnSpotsWithChargers
      case None    => returnSpotsWithoutChargers
    }
  }

  /**
    * samples from the set of discovered stalls using a multinomial logit function
    *
    * @param found            the discovered parkingZones
    * @param destinationUTM   coordinates of this request
    * @param parkingDuration  the duration of the forthcoming agent activity
    * @param valueOfTime      this agent's value of time
    * @param utilityFunction  a multinomial logit function for sampling utility from a set of parking alternatives
    * @param distanceFunction a function that computes the distance between two coordinates
    * @param random           random generator
    * @return the parking alternative that will be used for parking this agent's vehicle
    */
  def takeBestBySampling(
    found: Iterable[ParkingAlternative],
    destinationUTM: Coord,
    parkingDuration: Int,
    valueOfTime: Double,
    utilityFunction: MultinomialLogit[ParkingAlternative, String],
    distanceFunction: (Coord, Coord) => Double,
    random: Random
  ): Option[ParkingSearchResult] = {

    val alternatives: Iterable[(ParkingAlternative, Map[String, Double])] =
      found.map { parkingAlternative =>
        val ParkingAlternative(_, _, parkingZone, stallCoordinate) = parkingAlternative

        val parkingTicket: Double = parkingZone.pricingModel match {
          case Some(pricingModel) =>
            PricingModel.evaluateParkingTicket(pricingModel, parkingDuration)
          case None =>
            0.0
        }

        val installedCapacity = parkingZone.chargingPointType match {
          case Some(chargingPoint) => ChargingPointType.getChargingPointInstalledPowerInKw(chargingPoint)
          case None                => 0
        }

        val distance: Double = distanceFunction(destinationUTM, stallCoordinate)
        //val chargingCosts = (39 + random.nextInt((79 - 39) + 1)) / 100d // in $/kWh, assumed price range is $0.39 to $0.79 per kWh

        val averagePersonWalkingSpeed = 1.4; // in m/s
        val hourInSeconds = 3600;
        val maxAssumedInstalledChargingCapacity = 350; // in kW
        val dollarsInCents = 100;

        parkingAlternative ->
        Map(
          //"energyPriceFactor" -> chargingCosts, //currently assumed that these costs are included into parkingCostsPriceFactor
          "distanceFactor"          -> (distance / averagePersonWalkingSpeed / hourInSeconds) * valueOfTime, // in US$
          "installedCapacity"       -> (installedCapacity / maxAssumedInstalledChargingCapacity) * (parkingDuration / hourInSeconds) * valueOfTime, // in US$ - assumption/untested parkingDuration in seconds
          "parkingCostsPriceFactor" -> parkingTicket / dollarsInCents //in US$, assumptions for now: parking ticket costs include charging
        )
      }

    utilityFunction.sampleAlternative(alternatives.toMap, random).map { result =>
      val ParkingAlternative(taz, parkingType, parkingZone, coordinate) = result.alternativeType

      val utility = result.utility
      ParkingSearchResult(
        taz,
        parkingType,
        parkingZone,
        coordinate,
        utility
      )
    }
  }

  /**
    * finds the best parking zone id based on maximizing it's associated cost function evaluation
    *
    * @param destinationUTM   coordinates of this request
    * @param found            the discovered parkingZones
    * @param chargingInquiry  ChargingPreference per type of ChargingPoint
    * @param distanceFunction a function that computes the distance between two coordinates
    * @return the best parking option based on our cost function ranking evaluation
    */
  def takeBestByRanking(
    destinationUTM: Coord,
    valueOfTime: Double,
    parkingDuration: Double,
    found: Iterable[ParkingAlternative],
    chargingInquiry: Option[ChargingInquiry],
    distanceFunction: (Coord, Coord) => Double
  ): Option[ParkingSearchResult] = {

    found.foldLeft(Option.empty[ParkingSearchResult]) { (accOption, parkingAlternative) =>
      val (thisTAZ: TAZ, thisParkingType: ParkingType, thisParkingZone: ParkingZone, stallLocation: Coord) =
        (
          parkingAlternative.taz,
          parkingAlternative.parkingType,
          parkingAlternative.parkingZone,
          parkingAlternative.coord
        )

      val walkingDistance: Double = distanceFunction(destinationUTM, stallLocation)

      // rank this parking zone
      val thisRank = ParkingRanking.rankingValue(
        thisParkingZone,
        parkingDuration,
        walkingDistance,
        valueOfTime,
        chargingInquiry
      )

      // update fold accumulator with best-ranked parking zone along with relevant attributes
      accOption match {
        case None =>
          // the first zone found becomes the first accumulator
          Some {
            ParkingSearchResult(
              thisTAZ,
              thisParkingType,
              thisParkingZone,
              stallLocation,
              thisRank
            )
          }
        case Some(acc: ParkingSearchResult) =>
          // update the aggregate data, and optionally, update the best zone if it's ranking is superior
          if (acc.bestUtility < thisRank) {
            Some {
              acc.copy(
                bestTAZ = thisTAZ,
                bestParkingType = thisParkingType,
                bestParkingZone = thisParkingZone,
                bestCoord = stallLocation,
                bestUtility = thisRank
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
