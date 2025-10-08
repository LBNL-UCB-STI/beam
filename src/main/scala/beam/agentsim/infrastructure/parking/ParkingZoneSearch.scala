package beam.agentsim.infrastructure.parking

import beam.agentsim.agents.choice.logit.MultinomialLogit
import beam.agentsim.agents.vehicles.VehicleManager.{ReservedFor, TypeEnum}
import beam.agentsim.agents.vehicles.VehicleUse.{Freight, VehicleUse}
import beam.agentsim.infrastructure.ParkingInquiry.ParkingSearchMode.DoubleParkingAllowed
import beam.agentsim.infrastructure.ParkingInquiry.{ParkingActivityType, ParkingSearchMode}
import beam.agentsim.infrastructure.ParkingStall
import beam.agentsim.infrastructure.charging._
import beam.agentsim.infrastructure.parking.ParkingZoneFileUtils.VehicleRestrictionKey
import beam.agentsim.infrastructure.taz.TAZ
import beam.router.BeamRouter.Location
import beam.sim.config.BeamConfig
import beam.utils.MathUtils
import org.locationtech.jts.geom.Envelope
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.collections.QuadTree

import scala.annotation.tailrec
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

  /**
    * static configuration for all parking zone searches in this simulation
    *
    * @param searchParams parking search parameters in terms of distance and datastructure
    * @param boundingBox limiting coordinate bounds for simulation area
    * @param distanceFunction function which computes distance (based on underlying coordinate system)
    * @param searchExpansionFactor factor by which the radius is expanded
    */
  case class ParkingZoneSearchConfiguration(
    searchParams: BeamConfig.Beam.Agentsim.Agents.Parking.Search.Params,
    boundingBox: Envelope,
    distanceFunction: (Coord, Coord) => Double,
    estimatedMinParkingDurationInSeconds: Double,
    estimatedMeanEnRouteChargingDurationInSeconds: Double,
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
    searchQuadTree: SearchQuadTree,
    random: Random,
    originUTM: Option[Location],
    reservedFor: ReservedFor,
    parkingActivityType: ParkingActivityType,
    vehicleUse: VehicleUse
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
    parkingZoneIdsSeen: Set[Id[ParkingZoneId]] = Set.empty,
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
    costInDollars: Double,
    parkingDuration: Int,
    link: Option[Link]
  )

  case class SearchResult(zones: Set[TAZ], links: Option[Set[Link]], tazToLinks: Option[Map[TAZ, QuadTree[Link]]])

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
    parkingZoneLocSamplingFunction: (ParkingZone, Option[QuadTree[Link]]) => (Coord, Option[Link]),
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
      searchMode.lookupParkingZonesInNextSearchAreaUnlessThresholdReached(params.searchQuadTree) match {
        case Some(theseZones) =>
          // ParkingZones as as ParkingAlternatives
          val alternatives: Set[ParkingSearchAlternative] = {
            for {
              zone           <- theseZones.zones
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
              val zoneLinks = theseZones.tazToLinks.flatMap(_.get(zone))
              // Enhanced location sampling: prefer links if available
              val (stallLocation: Coord, linkLocation: Option[Link]) =
                parkingZoneLocSamplingFunction(parkingZone, zoneLinks)
              // end-of-day parking durations are set to zero, which will be mis-interpreted here
              val parkingDuration = Math.max(
                config.estimatedMinParkingDurationInSeconds.toInt, // at least a small duration of charging
                params.searchMode match {
                  case ParkingSearchMode.EnRouteCharging => config.estimatedMeanEnRouteChargingDurationInSeconds.toInt
                  case _                                 => params.parkingDuration.toInt
                }
              )
              val stallPriceInDollars: Double = parkingZone.pricingModel
                .map(PricingModel.evaluateParkingTicket(_, parkingDuration))
                .getOrElse(0.0)
              val parkingAlternative: ParkingAlternative =
                ParkingAlternative(
                  zone,
                  parkingZone.parkingType,
                  parkingZone,
                  stallLocation,
                  stallPriceInDollars,
                  parkingDuration,
                  linkLocation
                )
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
              val ParkingAlternative(taz, parkingType, parkingZone, coordinate, costInDollars, _, linkMaybe) =
                result.alternativeType

              // create a new stall instance. you win!
              val parkingStall = ParkingStall(
                taz.tazId,
                parkingZone.parkingZoneId,
                coordinate,
                costInDollars,
                parkingZone.chargingPointType,
                parkingZone.pricingModel,
                parkingType,
                params.parkingActivityType,
                parkingZone.reservedFor,
                linkMaybe
              )

              val theseParkingZoneIds: Set[Id[ParkingZoneId]] = alternatives.map {
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
    timeRestrictions: Map[VehicleRestrictionKey, Range]
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

  trait SearchQuadTree {
    def getRing(x: Double, y: Double, innerRadius: Double, outerRadius: Double): Option[SearchResult]
    def getElliptical(x1: Double, y1: Double, x2: Double, y2: Double, innerRadius: Double): Option[SearchResult]
  }

  object SearchQuadTree {

    private def buildQuadTreeFromLinks(links: Seq[Link]): QuadTree[Link] = {
      if (links.isEmpty) {
        new QuadTree[Link](-1, -1, 1, 1)
      } else {
        // Calculate bounds from links
        val coords = links.flatMap { link =>
          Seq(link.getFromNode.getCoord, link.getToNode.getCoord)
        }

        val minX = coords.map(_.getX).min
        val maxX = coords.map(_.getX).max
        val minY = coords.map(_.getY).min
        val maxY = coords.map(_.getY).max

        // Add small buffer
        val buffer = 100.0
        val quadTree = new QuadTree[Link](
          minX - buffer,
          minY - buffer,
          maxX + buffer,
          maxY + buffer
        )

        // Add links using their midpoint
        links.foreach { link =>
          val midX = 0.5 * (link.getFromNode.getCoord.getX + link.getToNode.getCoord.getX)
          val midY = 0.5 * (link.getFromNode.getCoord.getY + link.getToNode.getCoord.getY)
          quadTree.put(midX, midY, link)
        }

        quadTree
      }
    }

    case class TAZQuadTree(tazQuadTree: QuadTree[TAZ]) extends SearchQuadTree {

      override def getRing(x: Double, y: Double, innerRadius: Double, outerRadius: Double): Option[SearchResult] = {
        val result = Set.newBuilder[TAZ]
        tazQuadTree.getRing(x, y, innerRadius, outerRadius).forEach(taz => result += taz)
        val tazs = result.result()
        if (tazs.nonEmpty) Some(SearchResult(tazs, None, None)) else None
      }

      override def getElliptical(
        x1: Double,
        y1: Double,
        x2: Double,
        y2: Double,
        radius: Double
      ): Option[SearchResult] = {
        val result = Set.newBuilder[TAZ]
        tazQuadTree.getElliptical(x1, y1, x2, y2, radius).forEach(taz => result += taz)
        val tazs = result.result()
        if (tazs.nonEmpty) Some(SearchResult(tazs, None, None)) else None
      }
    }

    case class LinkQuadTree(
      linkQuadTree: QuadTree[Link],
      linkIdToTAZMapping: mutable.HashMap[Id[Link], Id[TAZ]],
      idToTAZMapping: mutable.HashMap[Id[TAZ], TAZ]
    ) extends SearchQuadTree {

      private def buildSearchResult(
        tazToLinks: mutable.HashMap[TAZ, mutable.ArrayBuffer[Link]]
      ): Option[SearchResult] = {
        if (tazToLinks.isEmpty) None
        else {
          val tazSet = tazToLinks.keySet.toSet

          // Pre-allocate with size hint
          val linkSetBuilder = Set.newBuilder[Link]
          linkSetBuilder.sizeHint(tazToLinks.values.map(_.size).sum)

          // Single pass: build both linkSet and quadtrees
          val tazToLinksQuadTree = tazToLinks.map { case (tazId, links) =>
            linkSetBuilder ++= links // add to set while iterating
            tazId -> buildQuadTreeFromLinks(links) // no need for .toSeq if buildQuadTree accepts ArrayBuffer
          }.toMap

          Some(SearchResult(tazSet, Some(linkSetBuilder.result()), Some(tazToLinksQuadTree)))
        }
      }

      override def getRing(x: Double, y: Double, innerRadius: Double, outerRadius: Double): Option[SearchResult] = {
        val tazToLinks = mutable.HashMap.empty[TAZ, mutable.ArrayBuffer[Link]]
        linkQuadTree.getRing(x, y, innerRadius, outerRadius).forEach { link =>
          val taz = idToTAZMapping(linkIdToTAZMapping(link.getId))
          tazToLinks.getOrElseUpdate(taz, mutable.ArrayBuffer.empty[Link]) += link
        }
        buildSearchResult(tazToLinks)
      }

      override def getElliptical(
        x1: Double,
        y1: Double,
        x2: Double,
        y2: Double,
        radius: Double
      ): Option[SearchResult] = {
        val tazToLinks = mutable.HashMap.empty[TAZ, mutable.ArrayBuffer[Link]]
        linkQuadTree.getElliptical(x1, y1, x2, y2, radius).forEach { link =>
          val taz = idToTAZMapping(linkIdToTAZMapping(link.getId))
          tazToLinks.getOrElseUpdate(taz, mutable.ArrayBuffer.empty[Link]) += link
        }
        buildSearchResult(tazToLinks)
      }
    }
  }

  trait SearchMode {

    def lookupParkingZonesInNextSearchAreaUnlessThresholdReached(
      searchQuadTree: SearchQuadTree
    ): Option[SearchResult]
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
        searchQuadTree: SearchQuadTree
      ): Option[SearchResult] = {
        if (thisInnerRadius > searchMaxRadius) None
        else {
          val result =
            searchQuadTree.getRing(destinationUTM.getX, destinationUTM.getY, thisInnerRadius, thisOuterRadius)
          thisInnerRadius = thisOuterRadius
          thisOuterRadius = thisOuterRadius * expansionFactor
          result
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
        searchQuadTree: SearchQuadTree
      ): Option[SearchResult] = {
        if (thisInnerDistance >= maxDistance) None
        else {
          val result = searchQuadTree.getElliptical(
            originUTM.getX,
            originUTM.getY,
            destinationUTM.getX,
            destinationUTM.getY,
            thisInnerDistance
          )
          thisInnerDistance = thisInnerDistance * expansionFactor
          result
        }
      }
    }

    def getMinMaxSearchDistance(
      config: ParkingZoneSearchConfiguration,
      params: ParkingZoneSearchParams
    ): (Double, Double) = {
      if (params.vehicleUse == Freight) {
        (config.searchParams.freight.minSearchRadius, config.searchParams.freight.maxSearchRadius)
      } else {
        (config.searchParams.passenger.minSearchRadius, config.searchParams.passenger.maxSearchRadius)
      }
    }

    def getInstance(
      config: ParkingZoneSearchConfiguration,
      params: ParkingZoneSearchParams
    ): SearchMode = {
      params.searchMode match {
        case ParkingSearchMode.EnRouteCharging =>
          EnrouteSearch(
            params.originUTM.getOrElse(throw new RuntimeException("Enroute process is expecting an origin location")),
            params.destinationUTM,
            config.searchParams.searchMaxDistanceRelativeToEllipseFoci,
            config.searchExpansionFactor,
            config.distanceFunction
          )

        case _ =>
          val (minRadius, maxRadius) = getMinMaxSearchDistance(config, params)
          val doubleParkingRadius = config.searchParams.searchDoubleParkingRadius
          val (startRadius, searchMaxRadius) = params.searchMode match {
            case DoubleParkingAllowed if doubleParkingRadius > 0 =>
              (math.min(minRadius, doubleParkingRadius), math.min(maxRadius, doubleParkingRadius))
            case _ =>
              (minRadius, maxRadius)
          }
          DestinationSearch(
            params.destinationUTM,
            startRadius,
            searchMaxRadius,
            config.searchExpansionFactor
          )
      }
    }
  }

}
