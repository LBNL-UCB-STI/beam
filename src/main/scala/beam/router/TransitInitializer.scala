package beam.router

import java.util
import java.util.Collections

import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
import beam.agentsim.events.SpaceTime
import beam.router.Modes.isOnStreetTransit
import beam.router.model.RoutingModel.TransitStopsInfo
import beam.router.model.{BeamLeg, BeamPath, RoutingModel}
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
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

class TransitInitializer(
  beamConfig: BeamConfig,
  dates: DateUtils,
  vehicleTypes: Map[Id[BeamVehicleType], BeamVehicleType],
  transportNetwork: TransportNetwork,
  travelTimeByLinkCalculator: (Double, Int, StreetMode) => Double
) extends ExponentialLazyLogging {
  private var numStopsNotFound = 0

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
  def initMap: Map[Id[BeamVehicle], (RouteInfo, ArrayBuffer[BeamLeg])] = {
    val start = System.currentTimeMillis()
    val activeServicesToday = transportNetwork.transitLayer.getActiveServicesForDate(dates.localBaseDate)
    val stopToStopStreetSegmentCache = mutable.Map[(Int, Int), Option[StreetPath]]()
    def pathWithoutStreetRoute(fromStop: Int, toStop: Int) = {
      val from = transportNetwork.transitLayer.streetVertexForStop.get(fromStop)
      val fromVertex = transportNetwork.streetLayer.vertexStore.getCursor(from)
      val to = transportNetwork.transitLayer.streetVertexForStop.get(toStop)
      val toVertex = transportNetwork.streetLayer.vertexStore.getCursor(to)

      val fromCoord =
        if (from != -1) new Coord(fromVertex.getLon, fromVertex.getLat)
        else {
          limitedWarn(fromStop)
          new Coord(-122, 38)
        }
      val toCoord =
        if (to != -1) new Coord(toVertex.getLon, toVertex.getLat)
        else {
          limitedWarn(toStop)
          new Coord(-122.001, 38.001)
        }

      (departureTime: Int, duration: Int, vehicleId: Id[Vehicle]) =>
        BeamPath(
          Vector(),
          Vector(),
          None,
          SpaceTime(fromCoord, departureTime),
          SpaceTime(toCoord, departureTime + duration),
          0
        )
    }

    def limitedWarn(stopIdx: Int): Unit = {
      if (numStopsNotFound < 5) {
        logger.warn("Stop {} not linked to street network.", stopIdx)
        numStopsNotFound = numStopsNotFound + 1
      } else if (numStopsNotFound == 5) {
        logger.warn(
          "Stop {} not linked to street network. Further warnings messages will be suppressed",
          stopIdx
        )
        numStopsNotFound = numStopsNotFound + 1
      }
    }

    def pathWithStreetRoute(fromStop: Int, toStop: Int, streetSeg: StreetPath) = {
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
          edges.map(_.intValue()).toVector,
          TravelTimeUtils.scaleTravelTime(
            streetSeg.getDuration,
            math.round(linksTimesAndDistances.travelTimes.tail.sum).toInt,
            linksTimesAndDistances.travelTimes
          ),
          None,
          SpaceTime(
            startEdge.getGeometry.getStartPoint.getX,
            startEdge.getGeometry.getStartPoint.getY,
            departureTime
          ),
          SpaceTime(
            endEdge.getGeometry.getEndPoint.getX,
            endEdge.getGeometry.getEndPoint.getY,
            departureTime + math.round(streetSeg.getDuration - scaledLinkTimes.head).toInt
          ),
          distance
        )
    }

    val transitData = transportNetwork.transitLayer.tripPatterns.asScala.toStream.flatMap { tripPattern =>
      val route = transportNetwork.transitLayer.routes.get(tripPattern.routeIndex)
      val mode = Modes.mapTransitMode(TransitLayer.getTransitModes(route.route_type))
      val transitPaths = tripPattern.stops.indices
        .sliding(2)
        .map {
          case IndexedSeq(fromStopIdx, toStopIdx) =>
            val fromStop = tripPattern.stops(fromStopIdx)
            val toStop = tripPattern.stops(toStopIdx)
            if (beamConfig.beam.routing.transitOnStreetNetwork && isOnStreetTransit(mode)) {
              stopToStopStreetSegmentCache.getOrElseUpdate(
                (fromStop, toStop),
                routeTransitPathThroughStreets(fromStop, toStop)
              ) match {
                case Some(streetSeg) =>
                  pathWithStreetRoute(fromStop, toStop, streetSeg)
                case None =>
                  pathWithoutStreetRoute(fromStop, toStop)
              }
            } else {
              pathWithoutStreetRoute(fromStop, toStop)
            }
        }
        .toSeq
      tripPattern.tripSchedules.asScala
        .filter(tripSchedule => activeServicesToday.get(tripSchedule.serviceCode))
        .map { tripSchedule =>
          // First create a unique id for this trip which will become the transit agent and vehicle id
          val tripVehId = Id.create(tripSchedule.tripId, classOf[BeamVehicle])
          val legs: ArrayBuffer[BeamLeg] = new ArrayBuffer()
          tripSchedule.departures.zipWithIndex.sliding(2).foreach {
            case Array((departureTimeFrom, from), (_, to)) =>
              val duration = tripSchedule.arrivals(to) - departureTimeFrom
              legs += BeamLeg(
                departureTimeFrom,
                mode,
                duration,
                transitPaths(from)(departureTimeFrom, duration, tripVehId)
              ).scaleToNewDuration(duration)
          }
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
    transitScheduleToCreate
  }

  private def routeTransitPathThroughStreets(
    fromStopIdx: Int,
    toStopIdx: Int
  ): Option[StreetPath] = {
    val profileRequest = new ProfileRequest()
    //Set timezone to timezone of transport network
    profileRequest.zoneId = transportNetwork.getTimeZone
    val fromVertex = transportNetwork.streetLayer.vertexStore
      .getCursor(transportNetwork.transitLayer.streetVertexForStop.get(fromStopIdx))
    val toVertex = transportNetwork.streetLayer.vertexStore
      .getCursor(transportNetwork.transitLayer.streetVertexForStop.get(toStopIdx))
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
    if (streetRouter.setOrigin(profileRequest.fromLat, profileRequest.fromLon)) {
      if (streetRouter.setDestination(profileRequest.toLat, profileRequest.toLon)) {
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
