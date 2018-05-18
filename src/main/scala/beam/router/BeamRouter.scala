package beam.router

import java.io.{ByteArrayOutputStream, ObjectOutput, ObjectOutputStream}
import java.time.{ZoneId, ZoneOffset, ZonedDateTime}
import java.util
import java.util.{Collections, UUID}
import java.util.concurrent.{ThreadLocalRandom, TimeUnit}

import akka.actor.Status.Success
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSelection, Address, Props, RelativeActorPath, RootActorPath, Stash}
import akka.cluster.{Cluster, MemberStatus}
import akka.cluster.ClusterEvent._
import akka.util.Timeout
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.agents.vehicles.BeamVehicleType.TransitVehicle
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.agents.{InitializeTrigger, TransitDriverAgent}
import beam.agentsim.events.SpaceTime
import beam.agentsim.scheduler.BeamAgentScheduler.ScheduleTrigger
import beam.router.BeamRouter._
import beam.router.Modes.BeamMode.{BUS, CABLE_CAR, FERRY, RAIL, SUBWAY, TRAM}
import beam.router.Modes.{BeamMode, isOnStreetTransit}
import beam.router.RoutingModel._
import beam.router.gtfs.FareCalculator
import beam.router.osm.TollCalculator
import beam.sim.BeamServices
import beam.sim.metrics.MetricsPrinter
import beam.sim.metrics.MetricsPrinter.{Print, Subscribe}
import com.conveyal.r5.api.util.LegMode
import com.conveyal.r5.profile.{ProfileRequest, StreetMode, StreetPath}
import com.conveyal.r5.streets.{StreetRouter, VertexStore}
import com.conveyal.r5.transit.{RouteInfo, TransitLayer, TransportNetwork}
import org.matsim.api.core.v01.network.Network
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.router.util.TravelTime
import org.matsim.vehicles.{Vehicle, VehicleType, VehicleUtils, Vehicles}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.duration._
import akka.pattern._

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future

class BeamRouter(services: BeamServices, transportNetwork: TransportNetwork, network: Network, eventsManager: EventsManager, transitVehicles: Vehicles, fareCalculator: FareCalculator, tollCalculator: TollCalculator) extends Actor with Stash with ActorLogging {
  // TODO Fix me!
  val servicePath = "/user/statsServiceProxy"
  val cluster = Cluster(context.system)
  val servicePathElements = servicePath match {
    case RelativeActorPath(elements) => elements
    case _ => throw new IllegalArgumentException(
      "servicePath [%s] is not a valid relative actor path" format servicePath)
  }

  private implicit val timeout = Timeout(50000, TimeUnit.SECONDS)

  private val config = services.beamConfig.beam.routing
//  private val routerWorker = context.actorOf(R5RoutingWorker.props(services, transportNetwork, network, fareCalculator, tollCalculator), "router-worker")

  private val metricsPrinter = context.actorOf(MetricsPrinter.props())
  private var numStopsNotFound = 0

  var nodes = Set.empty[Address]
  implicit val ex = context.system.dispatcher


  log.info(s"BeamRouter: ${self.path}")

  private val buffer: ArrayBuffer[RoutingRequest] = ArrayBuffer.empty[RoutingRequest]
  private val map: mutable.Map[UUID, ActorRef] = mutable.Map.empty
  private val BATCH_SIZE: Int = 20

  val tickTask = context.system.scheduler.schedule(2.seconds, 1.seconds, self, "tick")(context.dispatcher)

  override def preStart(): Unit = {
    cluster.subscribe(self, classOf[MemberEvent], classOf[ReachabilityEvent])
  }

  override def postStop(): Unit = {
    cluster.unsubscribe(self)
  }


  override def receive = {
    case InitTransit(scheduler) =>
      metricsPrinter ! Subscribe("histogram","**")
      // We have to send TransitInited as Broadcast because our R5RoutingWorker is stateful!
      val f = Future.sequence(nodes.map { address =>
        context.actorSelection(RootActorPath(address) / servicePathElements).resolveOne(10.seconds)
          .flatMap { serviceActor: ActorRef =>
            log.info("Sending InitTransit_v2  {}",  serviceActor)
            serviceActor ? InitTransit_v2(scheduler)
          }
      }).map { _ =>
        Success("success")
      }
      f.pipeTo(sender)
    case state: CurrentClusterState =>
      log.info("CurrentClusterState: {}", state)
      nodes = state.members.collect {
        case m if m.hasRole("compute") && m.status == MemberStatus.Up => m.address
      }
    case MemberUp(m) if m.hasRole("compute") =>
      log.info("MemberUp[compute]: {}", m)
      nodes += m.address
    case other: MemberEvent                         =>
      log.info("MemberEvent: {}", other)
      nodes -= other.member.address
    case UnreachableMember(m)                       =>
      log.info("UnreachableMember: {}", m)
      nodes -= m.address
    case ReachableMember(m) if m.hasRole("compute") =>
      log.info("ReachableMember: {}", m)
      nodes += m.address
    case other: RoutingRequest =>
      buffer +=(other)
      val actorRef = sender()
      map(other.id) = actorRef

    case "tick" if buffer.nonEmpty =>
      val msgs = buffer.take(BATCH_SIZE)
      msgs.foreach { m => buffer -= m }
      val address = nodes.toIndexedSeq(ThreadLocalRandom.current.nextInt(nodes.size))
      val service = context.actorSelection(RootActorPath(address) / servicePathElements)
      log.debug("Sending BatchRoutingRequests with {} messages to `{}`", msgs.size, service)
      service ! BatchRoutingRequests(msgs)

    case resp: BatchRoutingResponses =>
      resp.responses.foreach { resp =>
        val actorRef = map(resp.requestId)
        actorRef ! resp
      }

    case other =>
      val address = nodes.toIndexedSeq(ThreadLocalRandom.current.nextInt(nodes.size))
      val service = context.actorSelection(RootActorPath(address) / servicePathElements)
      //log.debug("Sending other `{}` to {}", other, service)
      service.forward(other)
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
  private def initTransit(scheduler: ActorRef) = {
    def createTransitVehicle(transitVehId: Id[Vehicle], route: RouteInfo, legs: Seq[BeamLeg]): Unit = {

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
          val transitDriverAgentProps = TransitDriverAgent.props(scheduler, services, transportNetwork, eventsManager, transitDriverId, vehicle, legs)
          val transitDriver = context.actorOf(transitDriverAgentProps, transitDriverId.toString)
          scheduler ! ScheduleTrigger(InitializeTrigger(0.0), transitDriver)

        case _ =>
          log.error(mode + " is not supported yet")
      }
    }

    val activeServicesToday = transportNetwork.transitLayer.getActiveServicesForDate(services.dates.localBaseDate)
    val stopToStopStreetSegmentCache = mutable.Map[(Int, Int), Option[StreetPath]]()
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
              val edges = streetSeg.getEdges.asScala
              val startEdge = transportNetwork.streetLayer.edgeStore.getCursor(edges.head)
              val endEdge = transportNetwork.streetLayer.edgeStore.getCursor(edges.last)
              (departureTime: Long, duration: Int, vehicleId: Id[Vehicle]) =>
                BeamPath(
                  edges.map(_.intValue()).toVector,
                  Option(TransitStopsInfo(fromStop, vehicleId, toStop)),
                  SpaceTime(startEdge.getGeometry.getStartPoint.getX, startEdge.getGeometry.getStartPoint.getY, departureTime),
                  SpaceTime(endEdge.getGeometry.getEndPoint.getX, endEdge.getGeometry.getEndPoint.getY, departureTime + streetSeg.getDuration),
                  streetSeg.getDistance.toDouble / 1000)
            case None =>
              val edgeIds = resolveFirstLastTransitEdges(fromStop, toStop)
              val startEdge = transportNetwork.streetLayer.edgeStore.getCursor(edgeIds.head)
              val endEdge = transportNetwork.streetLayer.edgeStore.getCursor(edgeIds.last)
              (departureTime: Long, duration: Int, vehicleId: Id[Vehicle]) =>
                BeamPath(
                  edgeIds,
                  Option(TransitStopsInfo(fromStop, vehicleId, toStop)),
                  SpaceTime(startEdge.getGeometry.getStartPoint.getX, startEdge.getGeometry.getStartPoint.getY, departureTime),
                  SpaceTime(endEdge.getGeometry.getEndPoint.getX, endEdge.getGeometry.getEndPoint.getY, departureTime + duration),
                  services.geo.distLatLon2Meters(new Coord(startEdge.getGeometry.getStartPoint.getX, startEdge.getGeometry.getStartPoint.getY),
                    new Coord(endEdge.getGeometry.getEndPoint.getX, endEdge.getGeometry.getEndPoint.getY))
                )
          }
        } else {
          val edgeIds = resolveFirstLastTransitEdges(fromStop, toStop)
          val startEdge = transportNetwork.streetLayer.edgeStore.getCursor(edgeIds.head)
          val endEdge = transportNetwork.streetLayer.edgeStore.getCursor(edgeIds.last)
          (departureTime: Long, duration: Int, vehicleId: Id[Vehicle]) =>
            BeamPath(
              edgeIds,
              Option(TransitStopsInfo(fromStop, vehicleId, toStop)),
              SpaceTime(startEdge.getGeometry.getStartPoint.getX, startEdge.getGeometry.getStartPoint.getY, departureTime),
              SpaceTime(endEdge.getGeometry.getEndPoint.getX, endEdge.getGeometry.getEndPoint.getY, departureTime + duration),
              services.geo.distLatLon2Meters(new Coord(startEdge.getGeometry.getStartPoint.getX, startEdge.getGeometry.getStartPoint.getY),
                new Coord(endEdge.getGeometry.getEndPoint.getX, endEdge.getGeometry.getEndPoint.getY))
            )
        }
      }.toSeq
      tripPattern.tripSchedules.asScala
        .filter(tripSchedule => activeServicesToday.get(tripSchedule.serviceCode))
        .map { tripSchedule =>
          // First create a unique for this trip which will become the transit agent and vehicle ids
          val tripVehId = Id.create(tripSchedule.tripId, classOf[Vehicle])
          var legs: Seq[BeamLeg] = Nil
          tripSchedule.departures.zipWithIndex.sliding(2).foreach { case Array((departureTimeFrom, from), (departureTimeTo, to)) =>
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

  /**
    * Does point2point routing request to resolve appropriated route between stops
    *
    * @param fromStopIdx from stop
    * @param toStopIdx   to stop
    * @return
    */
  private def routeTransitPathThroughStreets(fromStopIdx: Int, toStopIdx: Int) = {

    val profileRequest = new ProfileRequest()
    //Set timezone to timezone of transport network
    profileRequest.zoneId = transportNetwork.getTimeZone

    val fromVertex = transportNetwork.streetLayer.vertexStore.getCursor(transportNetwork.transitLayer.streetVertexForStop.get(fromStopIdx))
    val toVertex = transportNetwork.streetLayer.vertexStore.getCursor(transportNetwork.transitLayer.streetVertexForStop.get(toStopIdx))
    val fromPosTransformed = services.geo.snapToR5Edge(transportNetwork.streetLayer, new Coord(fromVertex.getLon, fromVertex.getLat), 100E3, StreetMode.WALK)
    val toPosTransformed = services.geo.snapToR5Edge(transportNetwork.streetLayer, new Coord(toVertex.getLon, toVertex.getLat), 100E3, StreetMode.WALK)

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

  private def resolveFirstLastTransitEdges(stopIdxs: Int*) = {
    val edgeIds: Vector[Int] = stopIdxs.map { stopIdx =>
      if (transportNetwork.transitLayer.streetVertexForStop.get(stopIdx) >= 0) {
        val stopVertex = transportNetwork.streetLayer.vertexStore.getCursor(transportNetwork.transitLayer
          .streetVertexForStop.get(stopIdx))
        val split = transportNetwork.streetLayer.findSplit(stopVertex.getLat, stopVertex.getLon, 10000, StreetMode.CAR)
        if (split != null) {
          split.edge
        } else {
          limitedWarn(stopIdx)
          createDummyEdgeFromVertex(stopVertex)
        }
      } else {
        limitedWarn(stopIdx)
        createDummyEdge
      }
    }.toVector.distinct
    edgeIds
  }

  private def limitedWarn(stopIdx: Int): Unit = {
    if (numStopsNotFound < 5) {
      log.warning(s"Stop $stopIdx not linked to street network.")
      numStopsNotFound = numStopsNotFound + 1
    } else if (numStopsNotFound == 5) {
      log.warning(s"Stop $stopIdx not linked to street network. Further warnings messages will be suppressed")
      numStopsNotFound = numStopsNotFound + 1
    }
  }

  private def createDummyEdge(): Int = {
    val fromVert = transportNetwork.streetLayer.vertexStore.addVertex(38, -122)
    val toVert = transportNetwork.streetLayer.vertexStore.addVertex(38.001, -122.001)
    transportNetwork.streetLayer.edgeStore.addStreetPair(fromVert, toVert, 1000, -1).getEdgeIndex
  }

  private def createDummyEdgeFromVertex(stopVertex: VertexStore#Vertex): Int = {
    val toVert = transportNetwork.streetLayer.vertexStore.addVertex(stopVertex.getLat + 0.001, stopVertex.getLon + 0.001)
    transportNetwork.streetLayer.edgeStore.addStreetPair(stopVertex.index, toVert, 1000, -1).getEdgeIndex
  }

}

object BeamRouter {
  type Location = Coord

  case class InitTransit(scheduler: ActorRef)

  case class InitTransit_v2(scheduler: ActorRef)

  case class TransitInited(transitSchedule: Map[Id[Vehicle], (RouteInfo, Seq[BeamLeg])])

  case class EmbodyWithCurrentTravelTime(leg: BeamLeg, vehicleId: Id[Vehicle], createdAt: ZonedDateTime)

  case class UpdateTravelTime(travelTime: TravelTime)

  /**
    * It is use to represent a request object
    *
    * @param origin                 start/from location of the route
    * @param destination            end/to location of the route
    * @param departureTime          time in seconds from base midnight
    * @param transitModes           what transit modes should be considered
    * @param streetVehicles         what vehicles should be considered in route calc
    * @param streetVehiclesAsAccess boolean (default true), if false, the vehicles considered for use on egress
    */
  case class RoutingRequest(origin: Location, destination: Location, departureTime: BeamTime,
                            transitModes: Seq[BeamMode], streetVehicles: Seq[StreetVehicle],
                            streetVehiclesAsAccess: Boolean = true,
                            createdAt: ZonedDateTime,
                            receivedAt: Option[ZonedDateTime] = None, id: UUID = UUID.randomUUID())

  /**
    * Message to respond a plan against a particular router request
    *
    * @param itineraries a vector of planned routes
    */
  case class RoutingResponse(itineraries: Seq[EmbodiedBeamTrip], requestCreatedAt: ZonedDateTime,
                             requestReceivedAt: ZonedDateTime,
                             createdAt: ZonedDateTime, requestId: UUID, routeCalcTimeMs: Long = 0,
                             receivedAt: Option[ZonedDateTime] = None)


  case class BatchRoutingRequests(requests: Seq[RoutingRequest])
  case class BatchRoutingResponses(responses: Seq[RoutingResponse], routesCalcTimeMs: Long = 0)

  def props(beamServices: BeamServices, transportNetwork: TransportNetwork, network: Network, eventsManager: EventsManager, transitVehicles: Vehicles, fareCalculator: FareCalculator, tollCalculator: TollCalculator) = Props(new BeamRouter(beamServices, transportNetwork, network, eventsManager, transitVehicles, fareCalculator, tollCalculator))
}