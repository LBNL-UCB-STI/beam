package beam.agentsim.infrastructure.parking

import beam.agentsim.agents.choice.logit.MultinomialLogit
import beam.agentsim.infrastructure.ParkingStall
import beam.agentsim.infrastructure.charging._
import beam.agentsim.infrastructure.taz.TAZ
import beam.router.BeamRouter.Location
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.collections.QuadTree

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.util.Random

object ParkingZoneSearch {

  /**
    * a nested structure to support a search over available parking attributes,
    * where traversal either terminates in an un-defined branch (no options found),
    * or a leaf, which contains the index of a ParkingZone in the ParkingZones lookup array
    * with the matching attributes. type parameter A is a tag from a graph partitioning, such as a TAZ,
    * or possibly an h3 key.
    */
  type ZoneSearchTree[A] = scala.collection.Map[Id[A], Map[ParkingType, Vector[Id[ParkingZoneId]]]]

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
    searchExpansionFactor: Double = 2.0
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
  case class ParkingZoneSearchParams[GEO](
    destinationUTM: Location,
    parkingDuration: Double,
    parkingMNLConfig: ParkingMNL.ParkingMNLConfig,
    zoneSearchTree: ZoneSearchTree[GEO],
    parkingZones: Map[Id[ParkingZoneId], ParkingZone[GEO]],
    zoneQuadTree: QuadTree[GEO],
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
  case class ParkingZoneSearchResult[GEO](
    parkingStall: ParkingStall,
    parkingZone: ParkingZone[GEO],
    parkingZoneIdsSeen: List[Id[ParkingZoneId]] = List.empty,
    parkingZonesSampled: List[(Id[ParkingZoneId], Option[ChargingPointType], ParkingType, Double)] = List.empty,
    iterations: Int = 1
  )

  /**
    * these are the alternatives that are generated/instantiated by a search
    * and then are selected by a sampling function
    *
    * @param geo geoLevel (TAZ, Link, etc) of the alternative
    * @param parkingType parking type of the alternative
    * @param parkingZone parking zone of the alternative
    * @param coord location sampled for this alternative
    * @param costInDollars expected cost for using this alternative
    */
  case class ParkingAlternative[GEO](
    geo: GEO,
    parkingType: ParkingType,
    parkingZone: ParkingZone[GEO],
    coord: Coord,
    costInDollars: Double
  )

  /**
    * used within a search to track search data
    *
    * @param parkingAlternative
    * @param utilityParameters
    */
  private[ParkingZoneSearch] case class ParkingSearchAlternative[GEO](
    parkingAlternative: ParkingAlternative[GEO],
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
  def incrementalParkingZoneSearch[GEO: GeoLevel](
    config: ParkingZoneSearchConfiguration,
    params: ParkingZoneSearchParams[GEO],
    parkingZoneFilterFunction: ParkingZone[GEO] => Boolean,
    parkingZoneLocSamplingFunction: ParkingZone[GEO] => Coord,
    parkingZoneMNLParamsFunction: ParkingAlternative[GEO] => Map[ParkingMNL.Parameters, Double],
    geoToTAZ: GEO => TAZ
  ): Option[ParkingZoneSearchResult[GEO]] = {
    import GeoLevel.ops._

    // find zones
    @tailrec
    def _search(
      thisInnerRadius: Double,
      thisOuterRadius: Double,
      parkingZoneIdsSeen: List[Id[ParkingZoneId]] = List.empty,
      parkingZoneIdsSampled: List[(Id[ParkingZoneId], Option[ChargingPointType], ParkingType, Double)] = List.empty,
      iterations: Int = 1
    ): Option[ParkingZoneSearchResult[GEO]] = {
      if (thisInnerRadius > config.searchMaxRadius) None
      else {

        // a lookup of the (next) search ring for TAZs
        val theseZones: List[GEO] =
          params.zoneQuadTree
            .getRing(params.destinationUTM.getX, params.destinationUTM.getY, thisInnerRadius, thisOuterRadius)
            .asScala
            .toList

        // ParkingZones as as ParkingAlternatives
        val alternatives: List[ParkingSearchAlternative[GEO]] = {
          for {
            zone                <- theseZones
            parkingTypesSubtree <- params.zoneSearchTree.get(zone.getId).toList
            parkingType         <- params.parkingTypes
            parkingZoneIds      <- parkingTypesSubtree.get(parkingType).toList
            parkingZoneId       <- parkingZoneIds
            parkingZone         <- ParkingZone.getParkingZone(params.parkingZones, parkingZoneId)
            if parkingZoneFilterFunction(parkingZone)
          } yield {
            // wrap ParkingZone in a ParkingAlternative
            val stallLocation: Coord = parkingZoneLocSamplingFunction(parkingZone)
            val stallPriceInDollars: Double =
              parkingZone.pricingModel match {
                case None => 0
                case Some(pricingModel) =>
                  PricingModel.evaluateParkingTicket(pricingModel, params.parkingDuration.toInt)
              }
            val parkingAlternative: ParkingAlternative[GEO] =
              ParkingAlternative(zone, parkingZone.parkingType, parkingZone, stallLocation, stallPriceInDollars)
            val parkingAlternativeUtility: Map[ParkingMNL.Parameters, Double] =
              parkingZoneMNLParamsFunction(parkingAlternative)
            ParkingSearchAlternative(
              parkingAlternative,
              parkingAlternativeUtility
            )
          }
        }

        if (alternatives.isEmpty) {
          _search(
            thisOuterRadius,
            thisOuterRadius * config.searchExpansionFactor,
            parkingZoneIdsSeen,
            parkingZoneIdsSampled,
            iterations + 1
          )
        } else {

          // remove any invalid parking alternatives
          val alternativesToSample: Map[ParkingAlternative[GEO], Map[ParkingMNL.Parameters, Double]] =
            alternatives.map { a =>
              a.parkingAlternative -> a.utilityParameters
            }.toMap

          val mnl: MultinomialLogit[ParkingAlternative[GEO], ParkingMNL.Parameters] =
            MultinomialLogit(
              Map.empty,
              params.parkingMNLConfig
            )

          mnl.sampleAlternative(alternativesToSample, params.random).map { result =>
            val ParkingAlternative(taz, parkingType, parkingZone, coordinate, costInDollars) = result.alternativeType

            // create a new stall instance. you win!
            val parkingStall = ParkingStall(
              taz.getId,
              geoToTAZ(taz).getId,
              parkingZone.parkingZoneId,
              coordinate,
              costInDollars,
              parkingZone.chargingPointType,
              parkingZone.pricingModel,
              parkingType,
              parkingZone.reservedFor
            )

            val theseParkingZoneIds: List[Id[ParkingZoneId]] = alternatives.map {
              _.parkingAlternative.parkingZone.parkingZoneId
            }
            val theseSampledParkingZoneIds: List[(Id[ParkingZoneId], Option[ChargingPointType], ParkingType, Double)] =
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
