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
import com.conveyal.r5.streets.{StreetRouter, VertexStore}
import com.conveyal.r5.transit.{RouteInfo, TransitLayer, TransportNetwork}
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.router.util.TravelTime
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
    val transitTrips = transportNetwork.transitLayer.tripPatterns.asScala.toStream
    val transitData = transitTrips.flatMap { tripPattern =>
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
                case None =>
                  val edgeIds = resolveFirstLastTransitEdges(fromStop, toStop)
                  val startEdge = transportNetwork.streetLayer.edgeStore
                    .getCursor(edgeIds.head)
                  val endEdge = transportNetwork.streetLayer.edgeStore
                    .getCursor(edgeIds.last)
                  (departureTime: Int, duration: Int, vehicleId: Id[Vehicle]) =>
                    BeamPath(
                      edgeIds,
                      Vector((duration.toDouble / 2.0).round.toInt, (duration.toDouble / 2.0).round.toInt), // for non-street based paths we don't have link ids so make up travel times
                      Option(TransitStopsInfo(fromStop, vehicleId, toStop)),
                      SpaceTime(
                        startEdge.getGeometry.getStartPoint.getX,
                        startEdge.getGeometry.getStartPoint.getY,
                        departureTime
                      ),
                      SpaceTime(
                        endEdge.getGeometry.getEndPoint.getX,
                        endEdge.getGeometry.getEndPoint.getY,
                        departureTime + duration
                      ),
                      services.geo.distLatLon2Meters(
                        new Coord(
                          startEdge.getGeometry.getStartPoint.getX,
                          startEdge.getGeometry.getStartPoint.getY
                        ),
                        new Coord(
                          endEdge.getGeometry.getEndPoint.getX,
                          endEdge.getGeometry.getEndPoint.getY
                        )
                      )
                    )
              }
            } else {
              val edgeIds = resolveFirstLastTransitEdges(fromStop, toStop)
              val startEdge = transportNetwork.streetLayer.edgeStore.getCursor(edgeIds.head)
              val endEdge = transportNetwork.streetLayer.edgeStore.getCursor(edgeIds.last)
              (departureTime: Int, duration: Int, vehicleId: Id[Vehicle]) =>
                BeamPath(
                  edgeIds,
                  Vector((duration.toDouble / 2.0).round.toInt, (duration.toDouble / 2.0).round.toInt), // for non-street based paths we don't have link ids so make up travel times
                  Option(TransitStopsInfo(fromStop, vehicleId, toStop)),
                  SpaceTime(
                    startEdge.getGeometry.getStartPoint.getX,
                    startEdge.getGeometry.getStartPoint.getY,
                    departureTime
                  ),
                  SpaceTime(
                    endEdge.getGeometry.getEndPoint.getX,
                    endEdge.getGeometry.getEndPoint.getY,
                    departureTime + duration
                  ),
                  services.geo.distLatLon2Meters(
                    new Coord(
                      startEdge.getGeometry.getStartPoint.getX,
                      startEdge.getGeometry.getStartPoint.getY
                    ),
                    new Coord(
                      endEdge.getGeometry.getEndPoint.getX,
                      endEdge.getGeometry.getEndPoint.getY
                    )
                  )
                )
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
              )
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
    val fromPosTransformed = services.geo.snapToR5Edge(
      transportNetwork.streetLayer,
      new Coord(fromVertex.getLon, fromVertex.getLat),
      100E3,
      StreetMode.WALK
    )
    val toPosTransformed = services.geo.snapToR5Edge(
      transportNetwork.streetLayer,
      new Coord(toVertex.getLon, toVertex.getLat),
      100E3,
      StreetMode.WALK
    )
    profileRequest.fromLon = fromPosTransformed.getX
    profileRequest.fromLat = fromPosTransformed.getY
    profileRequest.toLon = toPosTransformed.getX
    profileRequest.toLat = toPosTransformed.getY
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

  private def resolveFirstLastTransitEdges(stopIdxs: Int*): Vector[Int] = {
    val edgeIds: Vector[Int] = stopIdxs.map { stopIdx =>
      if (transportNetwork.transitLayer.streetVertexForStop.get(stopIdx) >= 0) {
        val stopVertex = transportNetwork.streetLayer.vertexStore.getCursor(
          transportNetwork.transitLayer.streetVertexForStop.get(stopIdx)
        )
        val split = transportNetwork.streetLayer.findSplit(
          stopVertex.getLat,
          stopVertex.getLon,
          10000,
          StreetMode.CAR
        )
        if (split != null) {
          split.edge
        } else {
          limitedWarn(stopIdx)
          createDummyEdgeFromVertex(stopVertex)
        }
      } else {
        limitedWarn(stopIdx)
        createDummyEdge()
      }
    }.toVector
    edgeIds
  }

  private def limitedWarn(stopIdx: Int): Unit = {
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

  private def createDummyEdge(): Int = {
    val fromVert = transportNetwork.streetLayer.vertexStore.addVertex(38, -122)
    val toVert =
      transportNetwork.streetLayer.vertexStore.addVertex(38.001, -122.001)
    transportNetwork.streetLayer.edgeStore
      .addStreetPair(fromVert, toVert, 1000, -1)
      .getEdgeIndex
  }

  private def createDummyEdgeFromVertex(stopVertex: VertexStore#Vertex): Int = {
    val toVert = transportNetwork.streetLayer.vertexStore
      .addVertex(stopVertex.getLat + 0.001, stopVertex.getLon + 0.001)
    transportNetwork.streetLayer.edgeStore
      .addStreetPair(stopVertex.index, toVert, 1000, -1)
      .getEdgeIndex
  }
}
