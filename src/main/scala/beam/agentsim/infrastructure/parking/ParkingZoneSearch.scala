package beam.agentsim.infrastructure.parking

import beam.agentsim.agents.choice.logit.MultinomialLogit
import beam.agentsim.agents.vehicles.VehicleCategory.VehicleCategory
import beam.agentsim.agents.vehicles.VehicleManager.{ReservedFor, TypeEnum}
import beam.agentsim.infrastructure.ParkingInquiry.ParkingSearchMode
import beam.agentsim.infrastructure.ParkingStall
import beam.agentsim.infrastructure.charging._
import beam.agentsim.infrastructure.taz.TAZ
import beam.router.BeamRouter.Location
import beam.utils.MathUtils
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.collections.QuadTree

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.collection.mutable
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
    * @param searchMaxRadius maximum distance for the search
    * @param searchMaxDistanceRelativeToEllipseFoci max distance to both foci of an ellipse
    * @param boundingBox limiting coordinate bounds for simulation area
    * @param distanceFunction function which computes distance (based on underlying coordinate system)
    * @param searchExpansionFactor factor by which the radius is expanded
    */
  case class ParkingZoneSearchConfiguration(
    searchStartRadius: Double,
    searchMaxRadius: Double,
    searchMaxDistanceRelativeToEllipseFoci: Double,
    boundingBox: Envelope,
    distanceFunction: (Coord, Coord) => Double,
    enrouteDuration: Double,
    fractionOfSameTypeZones: Double,
    minNumberOfSameTypeZones: Int,
    searchExpansionFactor: Double = 2.0
  )

  /**
    * dynamic data for a parking zone search, related to parking infrastructure and inquiry
    *
    * @param destinationUTM destination of inquiry
    * @param parkingDuration duration of the activity agent wants to park for
    * @param parkingMNLConfig utility function which evaluates [[ParkingAlternative]]s
    * @param zoneCollections a nested map lookup of [[ParkingZone]]s
    * @param parkingZones the stored state of all [[ParkingZone]]s
    * @param zoneQuadTree [[ParkingZone]]s are associated with a TAZ, which are themselves stored in this Quad Tree
    * @param random random number generator
    */
  case class ParkingZoneSearchParams(
    destinationUTM: Location,
    parkingDuration: Double,
    searchMode: ParkingSearchMode,
    parkingMNLConfig: ParkingMNL.ParkingMNLConfig,
    zoneCollections: Map[Id[TAZ], ParkingZoneCollection],
    parkingZones: Map[Id[ParkingZoneId], ParkingZone],
    zoneQuadTree: QuadTree[TAZ],
    random: Random,
    originUTM: Option[Location],
    reservedFor: ReservedFor
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
  case class ParkingAlternative(
    geo: TAZ,
    parkingType: ParkingType,
    parkingZone: ParkingZone,
    coord: Coord,
    costInDollars: Double
  )

  /**
    * used within a search to track search data
    *
    * @param parkingAlternative ParkingAlternative
    * @param utilityParameters Map[ParkingMNL.Parameters, Double]
    */
  private[ParkingZoneSearch] case class ParkingSearchAlternative(
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
      searchMode: SearchMode,
      parkingZoneIdsSeen: List[Id[ParkingZoneId]] = List.empty,
      parkingZoneIdsSampled: List[(Id[ParkingZoneId], Option[ChargingPointType], ParkingType, Double)] = List.empty,
      iterations: Int = 1
    ): Option[ParkingZoneSearchResult] = {
      // a lookup of the (next) search ring for TAZs
      searchMode.lookupParkingZonesInNextSearchAreaUnlessThresholdReached(params.zoneQuadTree) match {
        case Some(theseZones) =>
          // ParkingZones as as ParkingAlternatives
          val alternatives: List[ParkingSearchAlternative] = {
            for {
              zone           <- theseZones
              zoneCollection <- params.zoneCollections.get(zone.tazId).toSeq
              parkingZone <- zoneCollection.getFreeZones(
                config.fractionOfSameTypeZones,
                config.minNumberOfSameTypeZones,
                params.reservedFor,
                params.random
              )
              if parkingZoneFilterFunction(parkingZone)
            } yield {
              // wrap ParkingZone in a ParkingAlternative
              val stallLocation: Coord = parkingZoneLocSamplingFunction(parkingZone)
              val stallPriceInDollars: Double =
                parkingZone.pricingModel match {
                  case None => 0
                  case Some(pricingModel) if params.searchMode == ParkingSearchMode.EnRoute =>
                    PricingModel.evaluateParkingTicket(pricingModel, config.enrouteDuration.toInt)
                  case Some(pricingModel) =>
                    PricingModel.evaluateParkingTicket(pricingModel, params.parkingDuration.toInt)
                }
              val parkingAlternative: ParkingAlternative =
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
            _search(searchMode, parkingZoneIdsSeen, parkingZoneIdsSampled, iterations + 1)
          } else {
            // remove any invalid parking alternatives
            val alternativesToSample: Map[ParkingAlternative, Map[ParkingMNL.Parameters, Double]] =
              alternatives.map { a =>
                a.parkingAlternative -> a.utilityParameters
              }.toMap

            val mnl: MultinomialLogit[ParkingAlternative, ParkingMNL.Parameters] =
              MultinomialLogit(
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
                costInDollars,
                parkingZone.chargingPointType,
                parkingZone.pricingModel,
                parkingType,
                parkingZone.reservedFor
              )

              val theseParkingZoneIds: List[Id[ParkingZoneId]] = alternatives.map {
                _.parkingAlternative.parkingZone.parkingZoneId
              }
              val theseSampledParkingZoneIds
                : List[(Id[ParkingZoneId], Option[ChargingPointType], ParkingType, Double)] =
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
        case _ => None
      }
    }

    _search(SearchMode.getInstance(config, params))
  }

  /**
    * This class "describes" a parking zone (i.e. extended type of parking zone). This allows to search for similar
    * parking zones on other links or TAZes
    * @param parkingType the parking type (Residential, Workplace, Public)
    * @param chargingPointType the charging point type
    * @param pricingModel the pricing model
    * @param timeRestrictions the time restrictions
    */
  case class ParkingZoneInfo(
    parkingType: ParkingType,
    chargingPointType: Option[ChargingPointType],
    pricingModel: Option[PricingModel],
    timeRestrictions: Map[VehicleCategory, Range]
  )

  object ParkingZoneInfo {

    def describeParkingZone(zone: ParkingZone): ParkingZoneInfo = {
      new ParkingZoneInfo(
        zone.parkingType,
        zone.chargingPointType,
        zone.pricingModel,
        zone.timeRestrictions
      )
    }
  }

  class ParkingZoneCollection(val parkingZones: Seq[ParkingZone]) {

    private val publicFreeZones: Map[ParkingZoneInfo, mutable.Set[ParkingZone]] =
      parkingZones.view
        .filter(_.reservedFor.managerType == TypeEnum.Default)
        .groupBy(ParkingZoneInfo.describeParkingZone)
        .mapValues(zones => mutable.Set(zones: _*))
        .view
        .force

    private val reservedFreeZones: Map[ReservedFor, mutable.Set[ParkingZone]] =
      parkingZones.view
        .filter(_.reservedFor.managerType != TypeEnum.Default)
        .groupBy(_.reservedFor)
        .mapValues(zones => mutable.Set(zones: _*))
        .view
        .force

    def getFreeZones(
      fraction: Double,
      min: Int,
      reservedFor: ReservedFor,
      rnd: Random
    ): IndexedSeq[ParkingZone] = {
      (
        publicFreeZones.view.flatMap { case (_, zones) =>
          val numToTake = Math.max(MathUtils.doubleToInt(zones.size * fraction), min)
          MathUtils.selectRandomElements(zones, numToTake, rnd)
        } ++
        reservedFreeZones.getOrElse(reservedFor, Nil)
      ).toIndexedSeq
    }

    def claimZone(parkingZone: ParkingZone): Unit =
      if (parkingZone.stallsAvailable <= 0) {
        for (set <- getCorrespondingZoneSet(parkingZone)) set -= parkingZone
      }

    def releaseZone(parkingZone: ParkingZone): Unit =
      if (parkingZone.stallsAvailable > 0) {
        for (set <- getCorrespondingZoneSet(parkingZone)) set += parkingZone
      }

    private def getCorrespondingZoneSet(parkingZone: ParkingZone): Option[mutable.Set[ParkingZone]] =
      if (parkingZone.reservedFor.managerType == TypeEnum.Default) {
        publicFreeZones.get(ParkingZoneInfo.describeParkingZone(parkingZone))
      } else {
        reservedFreeZones.get(parkingZone.reservedFor)
      }
  }

  def createZoneCollections(zones: Seq[ParkingZone]): Map[Id[TAZ], ParkingZoneCollection] = {
    zones.groupBy(_.tazId).mapValues(new ParkingZoneCollection(_)).view.force
  }

  trait SearchMode {
    def lookupParkingZonesInNextSearchAreaUnlessThresholdReached(zoneQuadTree: QuadTree[TAZ]): Option[List[TAZ]]
  }

  object SearchMode {

    case class DestinationSearch(
      destinationUTM: Location,
      searchStartRadius: Double,
      searchMaxRadius: Double,
      expansionFactor: Double
    ) extends SearchMode {
      private var thisInnerRadius: Double = 0.0
      private var thisOuterRadius: Double = searchStartRadius

      override def lookupParkingZonesInNextSearchAreaUnlessThresholdReached(
        zoneQuadTree: QuadTree[TAZ]
      ): Option[List[TAZ]] = {
        if (thisInnerRadius > searchMaxRadius) None
        else {
          val result = zoneQuadTree
            .getRing(destinationUTM.getX, destinationUTM.getY, thisInnerRadius, thisOuterRadius)
            .asScala
            .toList
          thisInnerRadius = thisOuterRadius
          thisOuterRadius = thisOuterRadius * expansionFactor
          Some(result)
        }
      }
    }

    case class EnrouteSearch(
      originUTM: Location,
      destinationUTM: Location,
      searchMaxDistanceToFociInPercent: Double,
      expansionFactor: Double,
      distanceFunction: (Coord, Coord) => Double
    ) extends SearchMode {
      private val startDistance: Double = distanceFunction(originUTM, destinationUTM) * 1.01
      private val maxDistance: Double = startDistance * searchMaxDistanceToFociInPercent
      private var thisInnerDistance: Double = startDistance

      override def lookupParkingZonesInNextSearchAreaUnlessThresholdReached(
        zoneQuadTree: QuadTree[TAZ]
      ): Option[List[TAZ]] = {
        if (thisInnerDistance >= maxDistance) None
        else {
          val result = zoneQuadTree
            .getElliptical(originUTM.getX, originUTM.getY, destinationUTM.getX, destinationUTM.getY, thisInnerDistance)
            .asScala
            .toList
          thisInnerDistance = thisInnerDistance * expansionFactor
          Some(result)
        }
      }
    }

    def getInstance(
      config: ParkingZoneSearchConfiguration,
      params: ParkingZoneSearchParams
    ): SearchMode = {
      params.searchMode match {
        case ParkingSearchMode.EnRoute =>
          EnrouteSearch(
            params.originUTM.getOrElse(throw new RuntimeException("Enroute process is expecting an origin location")),
            params.destinationUTM,
            config.searchMaxDistanceRelativeToEllipseFoci,
            config.searchExpansionFactor,
            config.distanceFunction
          )
        case _ =>
          DestinationSearch(
            params.destinationUTM,
            config.searchStartRadius,
            config.searchMaxRadius,
            config.searchExpansionFactor
          )
      }
    }
  }
}
