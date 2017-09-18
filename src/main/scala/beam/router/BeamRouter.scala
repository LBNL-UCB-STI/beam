package beam.router

import java.util.UUID

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Stash, Terminated}
import akka.routing.{ActorRefRoutee, RoundRobinRoutingLogic, Router}
import beam.agentsim.agents.PersonAgent
import beam.agentsim.agents.vehicles.BeamVehicle.StreetVehicle
import beam.router.BeamRouter._
import beam.router.Modes.BeamMode
import beam.router.RoutingModel.{BeamTime, EmbodiedBeamTrip}
import beam.router.r5.NetworkCoordinator
import beam.sim.BeamServices
import org.matsim.api.core.v01.population.Activity
import org.matsim.api.core.v01.{Coord, Id, Identifiable}
import org.matsim.core.trafficmonitoring.TravelTimeCalculator

import scala.beans.BeanProperty


class BeamRouter(services: BeamServices) extends Actor with Stash with ActorLogging {
  var router: Router = _
  var networkCoordinator: ActorRef = _

  override def preStart(): Unit = {
    router = Router(RoundRobinRoutingLogic(), Vector.fill(5) {
      ActorRefRoutee(createAndWatch)
    })
    networkCoordinator = context.actorOf(NetworkCoordinator.props(services))
  }

  def receive = uninitialized

  // Uninitialized state
  def uninitialized: Receive = {
    case InitializeRouter =>
      log.info("Initializing Router.")
      networkCoordinator.forward(InitializeRouter)
      context.become(initializing)
    case RoutingRequest =>
      sender() ! RouterNeedInitialization
    case Terminated(r) =>
      handelTermination(r)
    case msg =>
      log.info(s"Unknown message[$msg] received by Router.")
  }

  // Initializing state
  def initializing: Receive = {
    case InitializeRouter =>
      log.debug("Router initialization in-progress...")
      stash()
    case RoutingRequest =>
      stash()
    case InitTransit =>
      stash()
    case RouterInitialized if sender().path.parent == self.path =>
      unstashAll()
      context.become(initialized)
    case Terminated(r) =>
      handelTermination(r)
    case msg =>
      log.info(s"Unknown message[$msg] received by Router.")
  }

  // Initialized state
  def initialized: Receive = {
    case w: RoutingRequest =>
      router.route(w, sender())
    case InitTransit =>
      router.route(InitTransit, sender())
    case InitializeRouter =>
      log.debug("Router already initialized.")
      sender() ! RouterInitialized
    case Terminated(r) =>
      handelTermination(r)
    case updateRequest: UpdateTravelTime =>
      log.info("Received TravelTimeCalculator")
      networkCoordinator ! updateRequest
    case msg => {
      log.info(s"Unknown message[$msg] received by Router.")
    }
  }

  private def handelTermination(r: ActorRef): Unit = {
    router = router.removeRoutee(r)
    router = router.addRoutee(createAndWatch)
  }

  private def createAndWatch(): ActorRef = {
    val r = context.actorOf(RoutingWorker.getRouterProps(services.beamConfig.beam.routing.routerClass, services))
    context watch r
  }
}

object BeamRouter {
  type Location = Coord

  def nextId = Id.create(UUID.randomUUID().toString, classOf[RoutingRequest])

  case object InitializeRouter
  case object RouterInitialized
  case object RouterNeedInitialization
  case object InitTransit
  case object TransitInited
  case class UpdateTravelTime(travelTimeCalculator: TravelTimeCalculator)

  /**
    * It is use to represent a request object
    * @param origin start/from location of the route
    * @param destination end/to location of the route
    * @param departureTime time in seconds from base midnight
    * @param transitModes what transit modes should be considered
    * @param streetVehicles what vehicles should be considered in route calc
    * @param personId
    */
  case class RoutingRequestTripInfo(origin: Location,
                                    destination: Location,
                                    departureTime: BeamTime,
                                    transitModes: Vector[BeamMode],
                                    streetVehicles: Vector[StreetVehicle],
                                    personId: Id[PersonAgent])

  /**
    * Message to request a route plan
    * @param id used to represent a request uniquely
    * @param params route information that is needs a plan
    */
  case class RoutingRequest(@BeanProperty id: Id[RoutingRequest],
                            params: RoutingRequestTripInfo) extends Identifiable[RoutingRequest]

  /**
    * Message to respond a plan against a particular router request
    * @param id same id that was send with request
    * @param itineraries a vector of planned routes
    */
  case class RoutingResponse(@BeanProperty id: Id[RoutingRequest],
                             itineraries: Vector[EmbodiedBeamTrip]) extends Identifiable[RoutingRequest]

  /**
    *
    * @param fromLocation
    * @param toOptions
    */
  case class BatchRoutingRequest(fromLocation: Location, toOptions: Vector[Location])

  object RoutingRequest {
    def apply(fromActivity: Activity, toActivity: Activity, departureTime: BeamTime, transitModes: Vector[BeamMode], streetVehicles: Vector[StreetVehicle], personId: Id[PersonAgent]): RoutingRequest = {
      new RoutingRequest(BeamRouter.nextId,
        RoutingRequestTripInfo(fromActivity.getCoord, toActivity.getCoord, departureTime,  Modes.filterForTransit(transitModes), streetVehicles, personId))
    }
    def apply(params : RoutingRequestTripInfo) = {
      new RoutingRequest(BeamRouter.nextId, params)
    }
  }

  def props(beamServices: BeamServices) = Props(classOf[BeamRouter], beamServices)
}