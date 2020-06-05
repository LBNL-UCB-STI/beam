package beam.agentsim.infrastructure.parking

import beam.agentsim.agents.choice.logit.{MultinomialLogit, UtilityFunctionOperation}
import scala.util.{Failure, Random, Success, Try}

import beam.agentsim.infrastructure.charging._
import beam.agentsim.infrastructure.taz.TAZ
import beam.router.BeamRouter.Location
import beam.sim.common.GeoUtils
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.collections.QuadTree
import scala.collection.JavaConverters._
import scala.annotation.tailrec

import beam.agentsim.infrastructure.ParkingStall

object ParkingZoneSearch {

  /**
    * a nested structure to support a search over available parking attributes,
    * where traversal either terminates in an un-defined branch (no options found),
    * or a leaf, which contains the index of a ParkingZone in the ParkingZones lookup array
    * with the matching attributes. type parameter A is a tag from a graph partitioning, such as a TAZ,
    * or possibly an h3 key.
    */
  type ZoneSearchTree[A] = Map[Id[A], Map[ParkingType, List[Int]]]

  // increases search radius by this factor at each iteration
  val SearchFactor: Double = 2.0

  // fallback value for stall pricing model evaluation
  val DefaultParkingPrice: Double = 0.0

  /**
    * static configuration for all parking zone searches in this simulation
    *
    * @param searchStartRadius radius of the first concentric ring search
    * @param searchMaxRadius maximum radius for the search
    * @param boundingBox limiting coordinate bounds for simulation area
    * @param distanceFunction function which computes distance (based on underlying coordinate system)
    * @param searchExpansionFactor factor by which the radius is expanded
    */
  case class ParkingZoneSearchConfiguration(
    searchStartRadius: Double,
    searchMaxRadius: Double,
    boundingBox: Envelope,
    distanceFunction: (Coord, Coord) => Double,
    searchExpansionFactor: Double = 2.0,
  )

  /**
    * dynamic data for a parking zone search, related to parking infrastructure and inquiry
    *
    * @param destinationUTM destination of inquiry
    * @param parkingDuration duration of the activity agent wants to park for
    * @param parkingMNLConfig utility function which evaluates [[ParkingAlternative]]s
    * @param zoneSearchTree a nested map lookup of [[ParkingZone]]s
    * @param parkingZones the stored state of all [[ParkingZone]]s
    * @param zoneQuadTree [[ParkingZone]]s are associated with a TAZ, which are themselves stored in this Quad Tree
    * @param random random number generator
    * @param parkingTypes the list of acceptable parking types allowed for this search
    */
  case class ParkingZoneSearchParams(
    destinationUTM: Location,
    parkingDuration: Double,
    parkingMNLConfig: ParkingMNL.ParkingMNLConfig,
    zoneSearchTree: ZoneSearchTree[TAZ],
    parkingZones: Array[ParkingZone],
    zoneQuadTree: QuadTree[TAZ],
    random: Random,
    parkingTypes: Seq[ParkingType] = ParkingType.AllTypes
  )

  /**
    * result of a [[ParkingZoneSearch]]
    *
    * @param parkingStall the embodied stall with sampled coordinate
    * @param parkingZone the [[ParkingZone]] associated with this stall
    * @param parkingZoneIdsSeen list of [[ParkingZone]] ids that were seen in this search
    */
  case class ParkingZoneSearchResult(
    parkingStall: ParkingStall,
    parkingZone: ParkingZone,
    parkingZoneIdsSeen: List[Int] = List.empty,
    parkingZonesSampled: List[(Int, Option[ChargingPointType], ParkingType, Double)] = List.empty,
    iterations: Int = 1
  )

  /**
    * these are the alternatives that are generated/instantiated by a search
    * and then are selected by a sampling function
    *
    * @param taz TAZ of the alternative
    * @param parkingType parking type of the alternative
    * @param parkingZone parking zone of the alternative
    * @param coord location sampled for this alternative
    * @param costInDollars expected cost for using this alternative
    */
  case class ParkingAlternative(
    taz: TAZ,
    parkingType: ParkingType,
    parkingZone: ParkingZone,
    coord: Coord,
    costInDollars: Double
  )

  /**
    * used within a search to track search data
    *
    * @param isValidAlternative
    * @param parkingAlternative
    * @param utilityParameters
    */
  private[ParkingZoneSearch] case class ParkingSearchAlternative(
    isValidAlternative: Boolean,
    parkingAlternative: ParkingAlternative,
    utilityParameters: Map[ParkingMNL.Parameters, Double]
  )

  /**
    * search for valid parking zones by incremental ring search and sample the highest utility alternative
    *
    * @param config static search parameters for all searches in a simulation
    * @param params inquiry and infrastructure data used as parameters for this search
    * @param parkingZoneFilterFunction a predicate to filter out types of stalls
    * @param parkingZoneLocSamplingFunction a function that samples [[Coord]]s for [[ParkingStall]]s
    * @param parkingZoneMNLParamsFunction a function that generates MNL parameters for a [[ParkingAlternative]]
    * @return if found, a suitable [[ParkingAlternative]]
    */
  def incrementalParkingZoneSearch(
    config: ParkingZoneSearchConfiguration,
    params: ParkingZoneSearchParams,
    parkingZoneFilterFunction: ParkingZone => Boolean,
    parkingZoneLocSamplingFunction: ParkingZone => Coord,
    parkingZoneMNLParamsFunction: ParkingAlternative => Map[ParkingMNL.Parameters, Double]
  ): Option[ParkingZoneSearchResult] = {

    // find zones
    @tailrec
    def _search(
      thisInnerRadius: Double,
      thisOuterRadius: Double,
      parkingZoneIdsSeen: List[Int] = List.empty,
      parkingZoneIdsSampled: List[(Int, Option[ChargingPointType], ParkingType, Double)] = List.empty,
      iterations: Int = 1
    ): Option[ParkingZoneSearchResult] = {
      if (thisInnerRadius > config.searchMaxRadius) None
      else {

        // a lookup of the (next) search ring for TAZs
        val theseZones: List[TAZ] =
          params.zoneQuadTree
            .getRing(params.destinationUTM.getX, params.destinationUTM.getY, thisInnerRadius, thisOuterRadius)
            .asScala
            .toList

        // ParkingZones as as ParkingAlternatives
        val alternatives: List[ParkingSearchAlternative] = {
          for {
            zone                <- theseZones
            parkingTypesSubtree <- params.zoneSearchTree.get(zone.tazId).toList
            parkingType         <- params.parkingTypes
            parkingZoneIds      <- parkingTypesSubtree.get(parkingType).toList
            parkingZoneId       <- parkingZoneIds
            parkingZone         <- ParkingZone.getParkingZone(params.parkingZones, parkingZoneId)
          } yield {
            // wrap ParkingZone in a ParkingAlternative
            val isValidParkingZone: Boolean = parkingZoneFilterFunction(parkingZone)
            val stallLocation: Coord = parkingZoneLocSamplingFunction(parkingZone)
            val stallPriceInDollars: Double =
              parkingZone.pricingModel match {
                case None => 0
                case Some(pricingModel) =>
                  PricingModel.evaluateParkingTicket(pricingModel, params.parkingDuration.toInt)
              }
            val parkingAlternative: ParkingAlternative =
              ParkingAlternative(zone, parkingType, parkingZone, stallLocation, stallPriceInDollars)
            val parkingAlternativeUtility: Map[ParkingMNL.Parameters, Double] =
              parkingZoneMNLParamsFunction(parkingAlternative)
            ParkingSearchAlternative(
              isValidParkingZone,
              parkingAlternative,
              parkingAlternativeUtility
            )
          }
        }

        val validParkingAlternatives: Int = alternatives.count { _.isValidAlternative }
        if (validParkingAlternatives == 0) {
          _search(
            thisOuterRadius,
            thisOuterRadius * config.searchExpansionFactor,
            parkingZoneIdsSeen,
            parkingZoneIdsSampled,
            iterations + 1
          )
        } else {

          // remove any invalid parking alternatives
          val alternativesToSample: Map[ParkingAlternative, Map[ParkingMNL.Parameters, Double]] =
            alternatives.flatMap { a =>
              if (a.isValidAlternative)
                Some { a.parkingAlternative -> a.utilityParameters } else
                None
            }.toMap

          val mnl: MultinomialLogit[ParkingAlternative, ParkingMNL.Parameters] =
            new MultinomialLogit(
              Map.empty,
              params.parkingMNLConfig
            )

          mnl.sampleAlternative(alternativesToSample, params.random).map { result =>
            val ParkingAlternative(taz, parkingType, parkingZone, coordinate, costInDollars) = result.alternativeType

            // create a new stall instance. you win!
            val parkingStall = ParkingStall(
              taz.tazId,
              parkingZone.parkingZoneId,
              coordinate,
              costInDollars.toDouble,
              parkingZone.chargingPointType,
              parkingZone.pricingModel,
              parkingType
            )

            val theseParkingZoneIds: List[Int] = alternatives.map { _.parkingAlternative.parkingZone.parkingZoneId }
            val theseSampledParkingZoneIds: List[(Int, Option[ChargingPointType], ParkingType, Double)] =
              alternativesToSample.map { altWithParams =>
                (
                  altWithParams._1.parkingZone.parkingZoneId,
                  altWithParams._1.parkingZone.chargingPointType,
                  altWithParams._1.parkingType,
                  altWithParams._1.costInDollars
                )

              }.toList
            ParkingZoneSearchResult(
              parkingStall,
              parkingZone,
              theseParkingZoneIds ++ parkingZoneIdsSeen,
              theseSampledParkingZoneIds ++ parkingZoneIdsSampled,
              iterations = iterations
            )
          }
        }
      }
    }
    _search(0, config.searchStartRadius)
  }
}
