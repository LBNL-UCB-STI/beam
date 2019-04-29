package beam.router

import java.util
import java.util.Collections

import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
import beam.agentsim.events.SpaceTime
import beam.router.Modes.BeamMode.{BUS, CABLE_CAR, FERRY, GONDOLA, RAIL, SUBWAY, TRAM}
import beam.router.Modes.isOnStreetTransit
import beam.router.model.RoutingModel.TransitStopsInfo
import beam.router.model.{BeamLeg, BeamPath, RoutingModel}
import beam.sim.BeamServices
import beam.utils.TravelTimeUtils
import beam.utils.logging.ExponentialLazyLogging
import com.conveyal.r5.api.util.LegMode
import com.conveyal.r5.profile.{ProfileRequest, StreetMode, StreetPath}
import com.conveyal.r5.streets.StreetRouter
import com.conveyal.r5.transit.{RouteInfo, TransitLayer, TransportNetwork, TripPattern}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.vehicles.{Vehicle, Vehicles}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.io.Source
import scala.util.Try

class TransitInitializer(
  services: BeamServices,
  transportNetwork: TransportNetwork,
  transitVehicles: Vehicles,
  travelTimeByLinkCalculator: (Int, Int, StreetMode) => Int
) extends ExponentialLazyLogging {
  private var numStopsNotFound = 0
  private val transitVehicleTypesByRoute: Map[String, Map[String, String]] = loadTransitVehicleTypesMap()

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
    val activeServicesToday = transportNetwork.transitLayer.getActiveServicesForDate(services.dates.localBaseDate)
    val stopToStopStreetSegmentCache = mutable.Map[(Int, Int), Option[StreetPath]]()
    val tripPatterns = transportNetwork.transitLayer.tripPatterns.asScala.toStream

    def allStopsAreLinked(pattern: TripPattern) = {
      !pattern.stops.map(stop => transportNetwork.transitLayer.streetVertexForStop.get(stop)).contains(-1)
    }

    def pathWithStreetSegment(fromStop: Int, toStop: Int, streetSeg: StreetPath) = {
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
        val distance = linksTimesAndDistances.distances.tail.sum
        BeamPath(
          edges.map(_.intValue()).toVector,
          TravelTimeUtils.scaleTravelTime(
            streetSeg.getDuration,
            linksTimesAndDistances.travelTimes.sum,
            linksTimesAndDistances.travelTimes
          ),
          Option(TransitStopsInfo(fromStop, vehicleId, toStop)),
          SpaceTime(
            startEdge.getGeometry.getStartPoint.getX,
            startEdge.getGeometry.getStartPoint.getY,
            departureTime
          ),
          SpaceTime(
            endEdge.getGeometry.getEndPoint.getX,
            endEdge.getGeometry.getEndPoint.getY,
            departureTime + streetSeg.getDuration
          ),
          distance
        )
    }

    def pathWithoutStreetSegment(fromStop: Int, toStop: Int) = {
      (departureTime: Int, duration: Int, vehicleId: Id[Vehicle]) =>
        val from = transportNetwork.transitLayer.streetVertexForStop.get(fromStop)
        val to = transportNetwork.transitLayer.streetVertexForStop.get(toStop)
        assert(from != -1, "Transit routes with unlinked stops are unsupported.")
        assert(to != -1, "Transit routes with unlinked stops are unsupported.")
        val fromVertex = transportNetwork.streetLayer.vertexStore.getCursor(from)
        val toVertex = transportNetwork.streetLayer.vertexStore.getCursor(to)
        BeamPath(
          Vector(),
          Vector(),
          Option(TransitStopsInfo(fromStop, vehicleId, toStop)),
          SpaceTime(
            fromVertex.getLon,
            fromVertex.getLat,
            departureTime
          ),
          SpaceTime(
            toVertex.getLon,
            toVertex.getLat,
            departureTime + duration
          ),
          services.geo.distLatLon2Meters(
            new Coord(
              fromVertex.getLon,
              fromVertex.getLat,
            ),
            new Coord(
              toVertex.getLon,
              toVertex.getLat,
            )
          )
        )
    }

    val transitData = tripPatterns
      .filter(tripPattern => allStopsAreLinked(tripPattern))
      .flatMap { tripPattern =>
        val route = transportNetwork.transitLayer.routes.get(tripPattern.routeIndex)
        val mode = Modes.mapTransitMode(TransitLayer.getTransitModes(route.route_type))
        val transitPaths = tripPattern.stops.indices
          .sliding(2)
          .map {
            case IndexedSeq(fromStopIdx, toStopIdx) =>
              val fromStop = tripPattern.stops(fromStopIdx)
              val toStop = tripPattern.stops(toStopIdx)
              if (services.beamConfig.beam.routing.transitOnStreetNetwork && isOnStreetTransit(mode)) {
                stopToStopStreetSegmentCache.getOrElseUpdate(
                  (fromStop, toStop),
                  routeTransitPathThroughStreets(fromStop, toStop)
                ) match {
                  case Some(streetSeg) =>
                    pathWithStreetSegment(fromStop, toStop, streetSeg)
                  case None =>
                    pathWithoutStreetSegment(fromStop, toStop)
                }
              } else {
                pathWithoutStreetSegment(fromStop, toStop)
              }
          }
          .toSeq
        tripPattern.tripSchedules.asScala
          .filter(tripSchedule => activeServicesToday.get(tripSchedule.serviceCode))
          .map { tripSchedule =>
            // First create a unique for this trip which will become the transit agent and vehicle ids
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
    transitScheduleToCreate.foreach {
      case (tripVehId, (route, legs)) =>
        createTransitVehicle(tripVehId, route, legs)
    }
    val end = System.currentTimeMillis()
    logger.info(
      "Initialized transit trips in {} ms. Keys: {}, Values: {}",
      end - start,
      transitScheduleToCreate.keySet.size,
      transitScheduleToCreate.values.size
    )
    transitScheduleToCreate
  }

  private def loadTransitVehicleTypesMap() = {
    Try(
      Source
        .fromFile(services.beamConfig.beam.agentsim.agents.vehicles.transitVehicleTypesByRouteFile)
        .getLines()
        .toList
        .tail
    ).getOrElse(List())
      .map(_.trim.split(","))
      .filter(_.length > 2)
      .groupBy(_(0))
      .mapValues(_.groupBy(_(1)).mapValues(_.head(2)))
  }

  def getVehicleType(route: RouteInfo, mode: Modes.BeamMode): BeamVehicleType = {
    val vehicleTypeId = Id.create(
      transitVehicleTypesByRoute
        .get(route.agency_id)
        .fold(None.asInstanceOf[Option[String]])(_.get(route.route_id))
        .getOrElse(mode.toString.toUpperCase + "-" + route.agency_id),
      classOf[BeamVehicleType]
    )

    if (services.vehicleTypes.contains(vehicleTypeId)) {
      services.vehicleTypes(vehicleTypeId)
    } else {
      logger.debug(
        "no specific vehicleType available for mode and transit agency pair '{}', using default vehicleType instead",
        vehicleTypeId.toString
      )
      //There has to be a default one defined
      services.vehicleTypes.getOrElse(
        Id.create(mode.toString.toUpperCase + "-DEFAULT", classOf[BeamVehicleType]),
        BeamVehicleType.defaultTransitBeamVehicleType
      )
    }
  }

  def createTransitVehicle(
    transitVehId: Id[Vehicle],
    route: RouteInfo,
    legs: Seq[BeamLeg]
  ): Option[BeamVehicle] = {
    val mode =
      Modes.mapTransitMode(TransitLayer.getTransitModes(route.route_type))

    val vehicleType = getVehicleType(route, mode)

    mode match {
      case (BUS | SUBWAY | TRAM | CABLE_CAR | RAIL | FERRY | GONDOLA) if vehicleType != null =>
        val powertrain = Option(vehicleType.primaryFuelConsumptionInJoulePerMeter)
          .map(new Powertrain(_))
          .getOrElse(Powertrain.PowertrainFromMilesPerGallon(Powertrain.AverageMilesPerGallon))

        val beamVehicleId = BeamVehicle.createId(transitVehId) //, Some(mode.toString)

        val vehicle: BeamVehicle = new BeamVehicle(
          beamVehicleId,
          powertrain,
          vehicleType
        ) // TODO: implement fuel level later as needed
        Some(vehicle)
      case _ =>
        logger.error("{} is not supported yet", mode)
        None
    }
  }

  private def routeTransitPathThroughStreets(fromStop: Int, toStop: Int): Option[StreetPath] = {
    val profileRequest = new ProfileRequest()
    //Set timezone to timezone of transport network
    profileRequest.zoneId = transportNetwork.getTimeZone
    val from = transportNetwork.transitLayer.streetVertexForStop.get(fromStop)
    val to = transportNetwork.transitLayer.streetVertexForStop.get(toStop)
    assert(from != -1, "Transit routes with unlinked stops are unsupported.")
    assert(to != -1, "Transit routes with unlinked stops are unsupported.")
    val fromVertex = transportNetwork.streetLayer.vertexStore.getCursor(from)
    val toVertex = transportNetwork.streetLayer.vertexStore.getCursor(to)
    profileRequest.fromLon = fromVertex.getLon
    profileRequest.fromLat = fromVertex.getLat
    profileRequest.toLon = toVertex.getLon
    profileRequest.toLat = toVertex.getLat
    profileRequest.fromTime = 0
    profileRequest.toTime = services.beamConfig.beam.routing.r5.departureWindow.toInt
    profileRequest.date = services.dates.localBaseDate
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
