package beam.router

import beam.agentsim.agents.TransitVehicleInitializer

import java.util
import java.util.Collections
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.events.SpaceTime
import beam.router.Modes.isOnStreetTransit
import beam.router.model.RoutingModel.TransitStopsInfo
import beam.router.model.{BeamLeg, BeamPath, RoutingModel}
import beam.router.r5.TravelTimeByLinkCalculator
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig
import beam.utils.logging.ExponentialLazyLogging
import beam.utils.{DateUtils, TravelTimeUtils}
import com.conveyal.r5.api.util.LegMode
import com.conveyal.r5.profile.{ProfileRequest, StreetMode, StreetPath}
import com.conveyal.r5.streets.StreetRouter
import com.conveyal.r5.transit.{RouteInfo, TransitLayer, TransportNetwork}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.vehicles.Vehicle

import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap

class TransitInitializer(
  beamConfig: BeamConfig,
  geo: GeoUtils,
  dates: DateUtils,
  transportNetwork: TransportNetwork,
  travelTimeByLinkCalculator: TravelTimeByLinkCalculator
) extends ExponentialLazyLogging {
  private val unlinkedStopIndices = TrieMap.empty[Int, Unit]
  private val unlinkedStopWarnThresholdAbsolute = 10
  private val unlinkedStopWarnThresholdFraction = 0.001
  private val unlinkedRouteWarnThresholdAbsolute = 2
  private val unlinkedRouteWarnThresholdFraction = 0.01
  private val unlinkedStopExampleLimit = 10
  private val unlinkedRouteExampleLimit = 5

  /*
   * Plan of action:
   * Each TripSchedule within each TripPattern represents a transit vehicle trip and will spawn a transitDriverAgent and
   * a vehicle
   * The arrivals/departures within the TripSchedules are vectors of the same length as the "stops" field in the
   * TripPattern
   * The stop IDs will be used to extract the Coordinate of the stop from the transitLayer (don't see exactly how yet)
   * Also should hold onto the route and trip IDs and use route to lookup the transit agency which ultimately should
   * be used to decide what type of vehicle to assign
   *
   */
  def initMap: Map[Id[BeamVehicle], (RouteInfo, Array[BeamLeg])] = {
    val start = System.currentTimeMillis()
    val activeServicesToday = transportNetwork.transitLayer.getActiveServicesForDate(dates.localBaseDate)
    val stopToStopStreetSegmentCache = TrieMap[(Int, Int), Option[StreetPath]]()
    val activeRouteIds = TrieMap.empty[String, Unit]
    val impactedRouteIds = TrieMap.empty[String, Unit]

    def pathWithoutStreetRoute(
      fromStop: Int,
      toStop: Int,
      fromStopIdx: Int,
      toStopIdx: Int
    ): (Int, Int, Id[Vehicle]) => BeamPath = {
      val from = transportNetwork.transitLayer.streetVertexForStop.get(fromStop)
      val fromVertex = transportNetwork.streetLayer.vertexStore.getCursor(from)
      val to = transportNetwork.transitLayer.streetVertexForStop.get(toStop)
      val toVertex = transportNetwork.streetLayer.vertexStore.getCursor(to)

      val fromCoord =
        if (from != -1) new Coord(fromVertex.getLon, fromVertex.getLat)
        else {
          recordUnlinkedStop(fromStop)
          new Coord(-122, 38)
        }
      val toCoord =
        if (to != -1) new Coord(toVertex.getLon, toVertex.getLat)
        else {
          recordUnlinkedStop(toStop)
          new Coord(-122.001, 38.001)
        }

      (departureTime: Int, duration: Int, vehicleId: Id[Vehicle]) =>
        BeamPath(
          linkIds = Array[Int](),
          linkTravelTime = Array[Double](),
          transitStops = Some(
            TransitStopsInfo(
              "",
              "",
              vehicleId,
              fromStopIdx,
              toStopIdx
            )
          ),
          startPoint = SpaceTime(fromCoord, departureTime),
          endPoint = SpaceTime(toCoord, departureTime + duration),
          distanceInM = geo.distLatLon2Meters(fromCoord, toCoord)
        )
    }

    def pathWithStreetRoute(
      fromStopIdx: Int,
      toStopIdx: Int,
      streetSeg: StreetPath
    ): (Int, Int, Id[Vehicle]) => BeamPath = {
      val edges = streetSeg.getEdges.asScala
      val startEdge = transportNetwork.streetLayer.edgeStore.getCursor(edges.head)
      val endEdge = transportNetwork.streetLayer.edgeStore.getCursor(edges.last)
      (departureTime: Int, _: Int, vehicleId: Id[Vehicle]) =>
        val linksTimesAndDistances = RoutingModel.linksToTimeAndDistance(
          edges.map(_.toInt).toIndexedSeq,
          departureTime,
          travelTimeByLinkCalculator,
          StreetMode.CAR,
          transportNetwork.streetLayer
        )
        val scaledLinkTimes = TravelTimeUtils.scaleTravelTime(
          streetSeg.getDuration,
          math.round(linksTimesAndDistances.travelTimes.tail.sum.toFloat),
          linksTimesAndDistances.travelTimes
        )
        val distance = linksTimesAndDistances.distances.tail.sum
        BeamPath(
          linkIds = edges.map(_.intValue()).toArray,
          linkTravelTime = TravelTimeUtils
            .scaleTravelTime(
              streetSeg.getDuration,
              math.round(linksTimesAndDistances.travelTimes.tail.sum).toInt,
              linksTimesAndDistances.travelTimes
            )
            .toArray,
          transitStops = Some(
            TransitStopsInfo(
              agencyId = "",
              routeId = "",
              vehicleId = vehicleId,
              fromIdx = fromStopIdx,
              toIdx = toStopIdx
            )
          ),
          startPoint = SpaceTime(
            startEdge.getGeometry.getStartPoint.getX,
            startEdge.getGeometry.getStartPoint.getY,
            departureTime
          ),
          endPoint = SpaceTime(
            endEdge.getGeometry.getEndPoint.getX,
            endEdge.getGeometry.getEndPoint.getY,
            departureTime + math.round(streetSeg.getDuration - scaledLinkTimes.head).toInt
          ),
          distanceInM = distance
        )
    }

    val transitData = transportNetwork.transitLayer.tripPatterns.asScala.par.flatMap { tripPattern =>
      val route = transportNetwork.transitLayer.routes.get(tripPattern.routeIndex)
      val routeKey = s"${route.agency_id}:${route.route_id}"
      val hasActiveSchedules =
        tripPattern.tripSchedules.asScala.exists(tripSchedule => activeServicesToday.get(tripSchedule.serviceCode))
      if (hasActiveSchedules) {
        activeRouteIds.put(routeKey, ())
        if (tripPattern.stops.exists(stop => transportNetwork.transitLayer.streetVertexForStop.get(stop) == -1)) {
          impactedRouteIds.put(routeKey, ())
        }
      }
      val mode = Modes.mapTransitMode(TransitLayer.getTransitModes(route.route_type))
      val transitPaths: Seq[(Int, Int, Id[Vehicle]) => BeamPath] = tripPattern.stops.indices
        .sliding(2)
        .map { case IndexedSeq(fromStopIdx, toStopIdx) =>
          val fromStop = tripPattern.stops(fromStopIdx)
          val toStop = tripPattern.stops(toStopIdx)
          if (beamConfig.beam.routing.transitOnStreetNetwork && isOnStreetTransit(mode)) {
            stopToStopStreetSegmentCache.getOrElseUpdate(
              (fromStop, toStop),
              routeTransitPathThroughStreets(fromStop, toStop)
            ) match {
              case Some(streetSeg) =>
                pathWithStreetRoute(fromStopIdx, toStopIdx, streetSeg)
              case None =>
                pathWithoutStreetRoute(fromStop, toStop, fromStopIdx, toStopIdx)
            }
          } else {
            pathWithoutStreetRoute(fromStop, toStop, fromStopIdx, toStopIdx)
          }
        }
        .toSeq

      tripPattern.tripSchedules.asScala
        .filter(tripSchedule => activeServicesToday.get(tripSchedule.serviceCode))
        .map { tripSchedule =>
          // First create a unique id for this trip which will become the transit agent and vehicle id
          val tripVehId = TransitVehicleInitializer.gtfsTripIdToBeamVehicleId(tripSchedule.tripId)
          val legs =
            tripSchedule.departures.zipWithIndex
              .sliding(2)
              .map { case Array((departureTimeFrom, from), (_, to)) =>
                val duration = tripSchedule.arrivals(to) - departureTimeFrom
                BeamLeg(
                  departureTimeFrom,
                  mode,
                  duration,
                  transitPaths(from)(departureTimeFrom, duration, tripVehId)
                ).scaleToNewDuration(duration)
              }
              .toArray
          (tripVehId, (route, legs))
        }
    }
    val transitScheduleToCreate = transitData.toMap
    val end = System.currentTimeMillis()
    logger.info(
      "Initialized transit trips in {} ms. Keys: {}, Values: {}",
      end - start,
      transitScheduleToCreate.keySet.size,
      transitScheduleToCreate.values.size
    )
    logUnlinkedStopSummary(activeRouteIds.keySet.size, impactedRouteIds.keys.toVector.sorted)
    transitScheduleToCreate
  }.seq

  private def routeTransitPathThroughStreets(
    fromStopIdx: Int,
    toStopIdx: Int
  ): Option[StreetPath] = {
    val fromStopIndex = transportNetwork.transitLayer.streetVertexForStop.get(fromStopIdx)
    val toStopIndex = transportNetwork.transitLayer.streetVertexForStop.get(toStopIdx)
    val linkRadiusMeters = beamConfig.beam.routing.r5.linkRadiusMeters
    if (fromStopIndex == -1 || toStopIndex == -1) {
      if (fromStopIndex == -1) recordUnlinkedStop(fromStopIdx)
      if (toStopIndex == -1) recordUnlinkedStop(toStopIdx)
      None
    } else {
      val profileRequest = new ProfileRequest()
      //Set timezone to timezone of transport network
      profileRequest.zoneId = transportNetwork.getTimeZone
      val fromVertex = transportNetwork.streetLayer.vertexStore.getCursor(fromStopIndex)
      val toVertex = transportNetwork.streetLayer.vertexStore.getCursor(toStopIndex)
      profileRequest.fromLon = fromVertex.getLon
      profileRequest.fromLat = fromVertex.getLat
      profileRequest.toLon = toVertex.getLon
      profileRequest.toLat = toVertex.getLat
      profileRequest.fromTime = 0
      profileRequest.toTime = beamConfig.beam.routing.r5.departureWindow.toInt
      profileRequest.date = dates.localBaseDate
      profileRequest.directModes = util.EnumSet.copyOf(Collections.singleton(LegMode.CAR))
      profileRequest.transitModes = null
      profileRequest.accessModes = profileRequest.directModes
      profileRequest.egressModes = null
      val streetRouter = new StreetRouter(transportNetwork.streetLayer)
      streetRouter.profileRequest = profileRequest
      streetRouter.streetMode = StreetMode.valueOf("CAR")
      streetRouter.timeLimitSeconds = profileRequest.streetTime * 60
      if (streetRouter.setOrigin(profileRequest.fromLat, profileRequest.fromLon, linkRadiusMeters)) {
        if (streetRouter.setDestination(profileRequest.toLat, profileRequest.toLon, linkRadiusMeters)) {
          streetRouter.route()
          val lastState = streetRouter.getState(streetRouter.getDestinationSplit)
          if (lastState != null) {
            Some(new StreetPath(lastState, transportNetwork, false))
          } else {
            None
          }
        } else {
          None
        }
      } else {
        None
      }
    }
  }

  private def recordUnlinkedStop(stopIdx: Int): Unit = {
    unlinkedStopIndices.put(stopIdx, ())
  }

  private def logUnlinkedStopSummary(activeRouteCount: Int, impactedRouteIds: Vector[String]): Unit = {
    val stopCount = unlinkedStopIndices.size
    if (stopCount > 0) {
      val totalStops = Option(transportNetwork.transitLayer.stopIdForIndex).map(_.size()).getOrElse(0)
      val stopWarnThreshold = math.max(
        unlinkedStopWarnThresholdAbsolute,
        math.ceil(totalStops * unlinkedStopWarnThresholdFraction).toInt
      )
      val impactedRouteCount = impactedRouteIds.size
      val routeWarnThreshold = math.max(
        unlinkedRouteWarnThresholdAbsolute,
        math.ceil(activeRouteCount * unlinkedRouteWarnThresholdFraction).toInt
      )
      val sampleStops = unlinkedStopIndices.keys.toVector.sorted.take(unlinkedStopExampleLimit).mkString(", ")
      val sampleRoutes = impactedRouteIds.take(unlinkedRouteExampleLimit).mkString(", ")
      val sampleText =
        if (sampleStops.nonEmpty) s" Sample internal stop indexes: [$sampleStops]."
        else ""
      val routeText =
        s" Affected active routes: $impactedRouteCount out of $activeRouteCount." +
        (if (sampleRoutes.nonEmpty) s" Sample routes: [$sampleRoutes]." else "")
      val explanation =
        s"$stopCount transit stops were not linked to the street network out of $totalStops total stops." +
        " This is often expected in clipped networks when GTFS stops remain but nearby walkable street links" +
        " were clipped away or removed as disconnected islands." +
        routeText +
        sampleText

      if (stopCount > stopWarnThreshold || impactedRouteCount > routeWarnThreshold) {
        logger.warn(
          explanation +
          s" Impact exceeds warning thresholds (stops=$stopWarnThreshold, activeRoutes=$routeWarnThreshold);" +
          " review the clipped network and GTFS/street alignment."
        )
      } else {
        logger.info(
          explanation +
          s" Impact is within the expected thresholds for clipped-network artifacts" +
          s" (stops=$stopWarnThreshold, activeRoutes=$routeWarnThreshold)."
        )
      }
    }
  }
}
