package beam.router

import java.io.{ByteArrayOutputStream, ObjectOutput, ObjectOutputStream}
import java.util
import java.util.Collections
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
import beam.router.r5.R5RoutingWorker
import beam.sim.BeamServices
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
  // private val routerWorker = context.actorOf(R5RoutingWorker.props(services, transportNetwork, network, fareCalculator, tollCalculator), "router-worker")
  private var numStopsNotFound = 0

  var nodes = Set.empty[Address]
  implicit val ex = context.system.dispatcher

  override def preStart(): Unit = {
    cluster.subscribe(self, classOf[MemberEvent], classOf[ReachabilityEvent])
  }

  override def postStop(): Unit = {
    cluster.unsubscribe(self)
  }

  override def receive = {
    case InitTransit(scheduler) =>
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


}

object BeamRouter {
  type Location = Coord

  case class InitTransit(scheduler: ActorRef)

  case class InitTransit_v2(scheduler: ActorRef)

  case class TransitInited(transitSchedule: Map[Id[Vehicle], (RouteInfo, Seq[BeamLeg])])

  case class EmbodyWithCurrentTravelTime(leg: BeamLeg, vehicleId: Id[Vehicle])

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
  case class RoutingRequest(origin: Location, destination: Location, departureTime: BeamTime, transitModes: Vector[BeamMode], streetVehicles: Vector[StreetVehicle], streetVehiclesAsAccess: Boolean = true)

  /**
    * Message to respond a plan against a particular router request
    *
    * @param itineraries a vector of planned routes
    */
  case class RoutingResponse(itineraries: Vector[EmbodiedBeamTrip])

  def props(beamServices: BeamServices, transportNetwork: TransportNetwork, network: Network, eventsManager: EventsManager, transitVehicles: Vehicles, fareCalculator: FareCalculator, tollCalculator: TollCalculator) = Props(new BeamRouter(beamServices, transportNetwork, network, eventsManager, transitVehicles, fareCalculator, tollCalculator))
}