package beam.router

import java.util
import java.util.Collections
import java.util.concurrent.TimeUnit

import akka.actor.Status.Success
import akka.actor.{Actor, ActorLogging, Props, Stash}
import akka.util.Timeout
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.agents.vehicles.BeamVehicleType.TransitVehicle
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.agents.{InitializeTrigger, PersonAgent, TransitDriverAgent}
import beam.agentsim.scheduler.BeamAgentScheduler.ScheduleTrigger
import beam.router.BeamRouter._
import beam.router.Modes.BeamMode.{BUS, CABLE_CAR, FERRY, RAIL, SUBWAY, TRAM}
import beam.router.Modes.{BeamMode, isOnStreetTransit}
import beam.router.RoutingModel._
import beam.router.gtfs.FareCalculator
import beam.router.osm.TollCalculator
import beam.router.r5.{BeamPointToPointQuery, R5RoutingWorker}
import beam.sim.BeamServices
import com.conveyal.r5.api.util.{LegMode, StreetEdgeInfo, StreetSegment}
import com.conveyal.r5.profile.{ProfileRequest, StreetMode}
import com.conveyal.r5.streets.EdgeStore
import com.conveyal.r5.transit.{RouteInfo, TransitLayer, TransportNetwork}
import org.matsim.api.core.v01.network.Network
import org.matsim.api.core.v01.population.Activity
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.router.util.TravelTime
import org.matsim.vehicles.{Vehicle, VehicleType, VehicleUtils, Vehicles}

import scala.collection.JavaConverters._
import scala.collection.mutable

class BeamRouter(services: BeamServices, transportNetwork: TransportNetwork, network: Network, eventsManager: EventsManager, transitVehicles: Vehicles, fareCalculator: FareCalculator, tollCalculator: TollCalculator) extends Actor with Stash with ActorLogging {
  private implicit val timeout = Timeout(50000, TimeUnit.SECONDS)

  private val config = services.beamConfig.beam.routing
  private val routerWorker = context.actorOf(R5RoutingWorker.props(services, transportNetwork, network, fareCalculator, tollCalculator), "router-worker")

  override def receive = {
    case InitTransit =>
      val transitSchedule = initTransit()
      routerWorker ! TransitInited(transitSchedule)
      sender ! Success("success")
    case updateRequest: UpdateTravelTime =>
      routerWorker.forward(updateRequest)
    case w: RoutingRequest =>
      routerWorker.forward(w)
  }


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
  private def initTransit() = {
    val activeServicesToday = transportNetwork.transitLayer.getActiveServicesForDate(services.dates.localBaseDate)
    val stopToStopStreetSegmentCache = mutable.Map[(Int, Int), Option[StreetSegment]]()
    val transitTrips = transportNetwork.transitLayer.tripPatterns.asScala.toStream
    val transitData = transitTrips.flatMap { tripPattern =>
      val route = transportNetwork.transitLayer.routes.get(tripPattern.routeIndex)
      val mode = Modes.mapTransitMode(TransitLayer.getTransitModes(route.route_type))
      val transitPaths = tripPattern.stops.indices.sliding(2).map { case IndexedSeq(fromStopIdx, toStopIdx) =>
        val fromStop = tripPattern.stops(fromStopIdx)
        val toStop = tripPattern.stops(toStopIdx)
        if (config.transitOnStreetNetwork && isOnStreetTransit(mode)) {
          stopToStopStreetSegmentCache.getOrElseUpdate((fromStop, toStop), routeTransitPathThroughStreets(fromStop, toStop)) match {
            case Some(streetSeg) =>
              var activeLinkIds = Vector[String]()
              for (edge: StreetEdgeInfo <- streetSeg.streetEdges.asScala) {
                activeLinkIds = activeLinkIds :+ edge.edgeId.toString
              }
              (departureTime: Long, duration: Int, vehicleId: Id[Vehicle]) => BeamPath(activeLinkIds, Option(TransitStopsInfo(fromStop, vehicleId, toStop)), StreetSegmentTrajectoryResolver(streetSeg, departureTime))
            case None =>
              val edgeIds = resolveFirstLastTransitEdges(fromStop, toStop)
              (departureTime: Long, duration: Int, vehicleId: Id[Vehicle]) => BeamPath(edgeIds, Option(TransitStopsInfo(fromStop, vehicleId, toStop)), TrajectoryByEdgeIdsResolver(transportNetwork.streetLayer, departureTime, duration))
          }
        } else {
          val edgeIds = resolveFirstLastTransitEdges(fromStop, toStop)
          (departureTime: Long, duration: Int, vehicleId: Id[Vehicle]) => BeamPath(edgeIds, Option(TransitStopsInfo(fromStop, vehicleId, toStop)), TrajectoryByEdgeIdsResolver(transportNetwork.streetLayer, departureTime, duration))
        }
      }.toSeq
      tripPattern.tripSchedules.asScala
        .filter(tripSchedule => activeServicesToday.get(tripSchedule.serviceCode))
        .map { tripSchedule =>
          // First create a unique for this trip which will become the transit agent and vehicle ids
          val tripVehId = Id.create(tripSchedule.tripId, classOf[Vehicle])
          var legs: Seq[BeamLeg] = Nil
          tripSchedule.departures.zipWithIndex.sliding(2).foreach { case Array((departureTimeFrom, from), (depatureTimeTo, to)) =>
            val duration = tripSchedule.arrivals(to) - departureTimeFrom
            legs :+= BeamLeg(departureTimeFrom.toLong, mode, duration, transitPaths(from)(departureTimeFrom.toLong, duration, tripVehId))
          }
          (tripVehId, (route, legs))
        }
    }
    val transitScheduleToCreate = transitData.toMap
    transitScheduleToCreate.foreach { case (tripVehId, (route, legs)) =>
      createTransitVehicle(tripVehId, route, legs)
    }
    log.info(s"Finished Transit initialization trips, ${transitData.length}")
    transitScheduleToCreate
  }

  private def createTransitVehicle(transitVehId: Id[Vehicle], route: RouteInfo, legs: Seq[BeamLeg]): Unit = {

    val mode = Modes.mapTransitMode(TransitLayer.getTransitModes(route.route_type))
    val vehicleTypeId = Id.create(mode.toString.toUpperCase + "-" + route.agency_id, classOf[VehicleType])

    val vehicleType = if (transitVehicles.getVehicleTypes.containsKey(vehicleTypeId)) {
      transitVehicles.getVehicleTypes.get(vehicleTypeId)
    } else {
      log.debug(s"no specific vehicleType available for mode and transit agency pair '${vehicleTypeId.toString})', using default vehicleType instead")
      transitVehicles.getVehicleTypes.get(Id.create(mode.toString.toUpperCase + "-DEFAULT", classOf[VehicleType]))
    }

    mode match {
      case (BUS | SUBWAY | TRAM | CABLE_CAR | RAIL | FERRY) if vehicleType != null =>
        val matSimTransitVehicle = VehicleUtils.getFactory.createVehicle(transitVehId, vehicleType)
        matSimTransitVehicle.getType.setDescription(mode.value)
        val consumption = Option(vehicleType.getEngineInformation).map(_.getGasConsumption).getOrElse(Powertrain
          .AverageMilesPerGallon)
        //        val transitVehProps = TransitVehicle.props(services, matSimTransitVehicle.getId, TransitVehicleData
        // (), Powertrain.PowertrainFromMilesPerGallon(consumption), matSimTransitVehicle, new Attributes())
        //        val transitVehRef = context.actorOf(transitVehProps, BeamVehicle.buildActorName(matSimTransitVehicle))
        val vehicle: BeamVehicle = new BeamVehicle(Powertrain.PowertrainFromMilesPerGallon(consumption),
          matSimTransitVehicle, None, TransitVehicle)
        services.vehicles += (transitVehId -> vehicle)
        val transitDriverId = TransitDriverAgent.createAgentIdFromVehicleId(transitVehId)
        val transitDriverAgentProps = TransitDriverAgent.props(services, transportNetwork, eventsManager, transitDriverId, vehicle, legs)
        val transitDriver = context.actorOf(transitDriverAgentProps, transitDriverId.toString)
        services.agentRefs += (transitDriverId.toString -> transitDriver)
        services.schedulerRef ! ScheduleTrigger(InitializeTrigger(0.0), transitDriver)

      case _ =>
        log.error(mode + " is not supported yet")
    }
  }

  /**
    * Does point2point routing request to resolve appropriated route between stops
    *
    * @param fromStopIdx from stop
    * @param toStopIdx   to stop
    * @return
    */
  private def routeTransitPathThroughStreets(fromStopIdx: Int, toStopIdx: Int) = {

    val pointToPointQuery = new BeamPointToPointQuery(services.beamConfig, transportNetwork, new EdgeStore.DefaultTravelTimeCalculator)
    val profileRequest = new ProfileRequest()
    //Set timezone to timezone of transport network
    profileRequest.zoneId = transportNetwork.getTimeZone

    val fromVertex = transportNetwork.streetLayer.vertexStore.getCursor(transportNetwork.transitLayer.streetVertexForStop.get(fromStopIdx))
    val toVertex = transportNetwork.streetLayer.vertexStore.getCursor(transportNetwork.transitLayer.streetVertexForStop.get(toStopIdx))
    var fromPosTransformed = services.geo.snapToR5Edge(transportNetwork.streetLayer, new Coord(fromVertex.getLon, fromVertex.getLat), 100E3, StreetMode.WALK)
    var toPosTransformed = services.geo.snapToR5Edge(transportNetwork.streetLayer, new Coord(toVertex.getLon, toVertex.getLat), 100E3, StreetMode.WALK)

    profileRequest.fromLon = fromPosTransformed.getX
    profileRequest.fromLat = fromPosTransformed.getY
    profileRequest.toLon = toPosTransformed.getX
    profileRequest.toLat = toPosTransformed.getY
    val time = WindowTime(0, services.beamConfig.beam.routing.r5.departureWindow)
    profileRequest.fromTime = time.fromTime
    profileRequest.toTime = time.toTime
    profileRequest.date = services.dates.localBaseDate
    profileRequest.directModes = util.EnumSet.copyOf(Collections.singleton(LegMode.CAR))
    profileRequest.transitModes = null
    profileRequest.accessModes = profileRequest.directModes
    profileRequest.egressModes = null
    val profileResponse = pointToPointQuery.getPlan(profileRequest)
    val closestDepartItinerary = profileResponse.options.asScala.headOption
    val legsBetweenStops = closestDepartItinerary match {
      case Some(option) =>
        val streetSeg = option.access.get(0)
        val itinerary = option.itinerary.get(0)
        var activeLinkIds = Vector[String]()
        for (edge: StreetEdgeInfo <- streetSeg.streetEdges.asScala) {
          activeLinkIds = activeLinkIds :+ edge.edgeId.toString
        }
        Some(streetSeg)
      case None =>
        None
    }
    legsBetweenStops
  }

  private def resolveFirstLastTransitEdges(stopIdxs: Int*) = {
    val edgeIds: Vector[String] = stopIdxs.map { stopIdx =>
      if (transportNetwork.transitLayer.streetVertexForStop.get(stopIdx) >= 0) {
        val stopVertex = transportNetwork.streetLayer.vertexStore.getCursor(transportNetwork.transitLayer
          .streetVertexForStop.get(stopIdx))
        val split = transportNetwork.streetLayer.findSplit(stopVertex.getLat, stopVertex.getLon, 100, StreetMode.CAR)
        if (split != null) {
          split.edge.toString
        } else {
          log.warning(s"Stop ${stopIdx} not linked to street network.")
          ""
        }
      } else {
        log.warning(s"Stop ${stopIdx} not linked to street network.")
        ""
      }
    }.toVector.distinct
    edgeIds
  }

}

object BeamRouter {
  type Location = Coord

  case object InitTransit

  case class TransitInited(transitSchedule: Map[Id[Vehicle], (RouteInfo, Seq[BeamLeg])])

  case class UpdateTravelTime(travelTime: TravelTime)

  /**
    * It is use to represent a request object
    *
    * @param origin                 start/from location of the route
    * @param destination            end/to location of the route
    * @param departureTime          time in seconds from base midnight
    * @param transitModes           what transit modes should be considered
    * @param streetVehicles         what vehicles should be considered in route calc
    * @param personId
    * @param streetVehiclesAsAccess boolean (default true), if false, the vehicles considered for use on egress
    */
  case class RoutingRequestTripInfo(personId: Id[PersonAgent],
                                    origin: Location,
                                    destination: Location,
                                    departureTime: BeamTime,
                                    transitModes: Vector[BeamMode],
                                    streetVehicles: Vector[StreetVehicle],
                                    streetVehiclesAsAccess: Boolean = true
                                   )

  /**
    * Message to request a route plan
    *
    * @param params route information that is needs a plan
    */
  case class RoutingRequest(params: RoutingRequestTripInfo)

  /**
    * Message to respond a plan against a particular router request
    *
    * @param itineraries a vector of planned routes
    */
  case class RoutingResponse(itineraries: Vector[EmbodiedBeamTrip])

  object RoutingRequest {
    def apply(fromActivity: Activity, toActivity: Activity, departureTime: BeamTime, transitModes: Vector[BeamMode], streetVehicles: Vector[StreetVehicle], personId: Id[PersonAgent], streetVehiclesAsAccess: Boolean = true): RoutingRequest = {
      new RoutingRequest(RoutingRequestTripInfo(personId, fromActivity.getCoord, toActivity.getCoord, departureTime, Modes.filterForTransit(transitModes), streetVehicles, streetVehiclesAsAccess))
    }

    def apply(params: RoutingRequestTripInfo): RoutingRequest = {
      new RoutingRequest(params)
    }
  }

  def props(beamServices: BeamServices, transportNetwork: TransportNetwork, network: Network, eventsManager: EventsManager, transitVehicles: Vehicles, fareCalculator: FareCalculator, tollCalculator: TollCalculator) = Props(new BeamRouter(beamServices, transportNetwork, network, eventsManager, transitVehicles, fareCalculator, tollCalculator))
}