package beam.router

import java.util.concurrent.TimeUnit

import akka.actor.Status.Success
import akka.actor.{Actor, ActorLogging, ActorRef, Props, Stash}
import akka.util.Timeout
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.agents.{InitializeTrigger, TransitDriverAgent}
import beam.agentsim.scheduler.BeamAgentScheduler.ScheduleTrigger
import beam.router.BeamRouter._
import beam.router.Modes.BeamMode
import beam.router.RoutingModel._
import beam.router.gtfs.FareCalculator
import beam.router.osm.TollCalculator
import beam.router.r5.R5RoutingWorker
import beam.sim.metrics.MetricsPrinter
import beam.sim.metrics.MetricsPrinter.{Print, Subscribe}
import beam.sim.{BeamServices, TransitInitializer}
import com.conveyal.r5.transit.{RouteInfo, TransportNetwork}
import org.matsim.api.core.v01.network.Network
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.router.util.TravelTime
import org.matsim.vehicles.{Vehicle, Vehicles}

class BeamRouter(
                  services: BeamServices,
                  transportNetwork: TransportNetwork,
                  network: Network,
                  eventsManager: EventsManager,
                  transitVehicles: Vehicles,
                  fareCalculator: FareCalculator,
                  tollCalculator: TollCalculator,
                  useLocalWorker: Boolean = true
                ) extends Actor
  with Stash
  with ActorLogging {
  private implicit val timeout: Timeout = Timeout(50000, TimeUnit.SECONDS)

  private val config = services.beamConfig.beam.routing

  val routerWorker = //if (useLocalWorker) {
    context.actorOf(
      R5RoutingWorker.props(services, transportNetwork, network, fareCalculator, tollCalculator),
      "router-worker"
    )
  // }

  private val metricsPrinter = context.actorOf(MetricsPrinter.props())
  private var numStopsNotFound = 0

  override def receive: PartialFunction[Any, Unit] = {
    case InitTransit(scheduler) =>
      val initializer = new TransitInitializer(services, transportNetwork, transitVehicles)
      val transits = initializer.initMap
      initDriverAgents(initializer, scheduler, transits)
      metricsPrinter ! Subscribe("histogram", "**")
      routerWorker ! TransitInited(transits)
      sender ! Success("success")
    case msg: UpdateTravelTime =>
      metricsPrinter ! Print(
        Seq(
          "cache-router-time",
          "noncache-router-time",
          "noncache-transit-router-time",
          "noncache-nontransit-router-time"
        ),
        Nil
      )
      routerWorker.forward(msg)
    case GetMatSimNetwork =>
      sender ! MATSimNetwork(network)
    case other =>
      routerWorker.forward(other)
  }

  private def initDriverAgents(
                                initializer: TransitInitializer,
                                scheduler: ActorRef,
                                transits: Map[Id[Vehicle], (RouteInfo, Seq[BeamLeg])]
                              ): Unit = {
    transits.foreach {
      case (tripVehId, (route, legs)) =>
        initializer.createTransitVehicle(tripVehId, route, legs).foreach { vehicle =>
          services.vehicles += (tripVehId -> vehicle)
          val transitDriverId =
            TransitDriverAgent.createAgentIdFromVehicleId(tripVehId)
          val transitDriverAgentProps = TransitDriverAgent.props(
            scheduler,
            services,
            transportNetwork,
            eventsManager,
            transitDriverId,
            vehicle,
            legs
          )
          val transitDriver =
            context.actorOf(transitDriverAgentProps, transitDriverId.toString)
          scheduler ! ScheduleTrigger(InitializeTrigger(0.0), transitDriver)
        }
    }
  }
}

object BeamRouter {
  type Location = Coord

  case class InitTransit(scheduler: ActorRef)

  case class TransitInited(transitSchedule: Map[Id[Vehicle], (RouteInfo, Seq[BeamLeg])])

  case class EmbodyWithCurrentTravelTime(leg: BeamLeg, vehicleId: Id[Vehicle])

  case class UpdateTravelTime(travelTime: TravelTime)

  case class R5Network(transportNetwork: TransportNetwork)

  case object GetTravelTime

  case class MATSimNetwork(network: Network)

  case object GetMatSimNetwork

  /**
    * It is use to represent a request object
    *
    * @param origin                 start/from location of the route
    * @param destination            end/to location of the route
    * @param departureTime          time in seconds from base midnight
    * @param transitModes           what transit modes should be considered
    * @param streetVehicles         what vehicles should be considered in route calc
    * @param streetVehiclesUseIntermodalUse boolean (default true), if false, the vehicles considered for use on egress
    */
  case class RoutingRequest(
                             origin: Location,
                             destination: Location,
                             departureTime: BeamTime,
                             transitModes: Vector[BeamMode],
                             streetVehicles: Vector[StreetVehicle],
                             streetVehiclesUseIntermodalUse: IntermodalUse = Access,
                             mustParkAtEnd: Boolean = false
                           ) {
    lazy val requestId: Int = this.hashCode()
  }

  sealed trait IntermodalUse
  case object Access extends IntermodalUse
  case object Egress extends IntermodalUse
  case object AccessAndEgress extends IntermodalUse

  /**
    * Message to respond a plan against a particular router request
    *
    * @param itineraries a vector of planned routes
    */
  case class RoutingResponse(itineraries: Vector[EmbodiedBeamTrip], requestId: Option[Int] = None)

  def props(
             beamServices: BeamServices,
             transportNetwork: TransportNetwork,
             network: Network,
             eventsManager: EventsManager,
             transitVehicles: Vehicles,
             fareCalculator: FareCalculator,
             tollCalculator: TollCalculator
           ) =
    Props(
      new BeamRouter(
        beamServices,
        transportNetwork,
        network,
        eventsManager,
        transitVehicles,
        fareCalculator,
        tollCalculator
      )
    )
}