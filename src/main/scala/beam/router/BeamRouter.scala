package beam.router

import java.util.UUID

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Stash, Terminated}
import akka.routing.{ActorRefRoutee, RoundRobinRoutingLogic, Router}
import beam.agentsim.agents.PersonAgent
import beam.router.BeamRouter.{InitializeRouter, RouterInitialized, RoutingRequest}
import beam.router.Modes.BeamMode
import beam.router.RoutingModel.{BeamTime, BeamTrip}
import beam.router.BeamRouter.{InitializeRouter, RouterInitialized, RouterNeedInitialization, RoutingRequest}
import beam.sim.BeamServices
import org.matsim.api.core.v01.population.Activity
import org.matsim.api.core.v01.{Coord, Id, Identifiable}
import org.matsim.facilities.Facility

import scala.beans.BeanProperty

class BeamRouter(beamServices: BeamServices) extends Actor with Stash with ActorLogging {
  var services: BeamServices = beamServices
  var router = Router(RoundRobinRoutingLogic(), Vector.fill(5) {
    ActorRefRoutee(createAndWatch)
  })

  def receive = uninitialized

    // Uninitialized state
  def uninitialized: Receive = {
    case InitializeRouter =>
      log.info("Initializing Router.")
      router.route(InitializeRouter, sender())
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
    case RouterInitialized if sender().path.parent == self.path =>
      unstashAll()
      context.become(initialized)
    case Terminated(r) =>
      handelTermination(r)
    case msg =>
      log.info(s"Unknown message[$msg] received by Router.")
  }

  def initialized: Receive = {
    case w: RoutingRequest =>
      router.route(w, sender())
    case InitializeRouter =>
      log.debug("Router already initialized.")
      sender() ! RouterInitialized
    case Terminated(r) =>
      handelTermination(r)
    case msg =>
      log.info(s"Unknown message[$msg] received by Router.")
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
  type RouteLocation = Coord

  def nextId = Id.create(UUID.randomUUID().toString, classOf[RoutingRequest])
  sealed trait RouterMessage
  case object InitializeRouter extends RouterMessage
  case object RouterInitialized extends RouterMessage
  case object RouterNeedInitialization extends RouterMessage

  case class RoutingRequestParams(departureTime: BeamTime, accessMode: Vector[BeamMode], personId: Id[PersonAgent])
  case class RoutingRequest(@BeanProperty id: Id[RoutingRequest], from: RouteLocation, destination: RouteLocation,
                            params: RoutingRequestParams) extends RouterMessage with Identifiable[RoutingRequest]
  case class RoutingResponse(requestId: Id[RoutingRequest], itinerary: Vector[BeamTrip]) extends RouterMessage {
  }

  /**
    *
    * @param fromFacility
    * @param toOptions
    */
  case class BatchRoutingRequest(fromFacility: Facility[_ <: Facility[_]], toOptions: Vector[Facility[_ <: Facility[_]]])

  object RoutingRequest {
    def apply(fromActivity: Activity, toActivity: Activity, departureTime: BeamTime, modes: Vector[BeamMode], personId: Id[PersonAgent]): RoutingRequest = {
      new RoutingRequest(BeamRouter.nextId, fromActivity.getCoord, toActivity.getCoord,
        RoutingRequestParams(departureTime, modes, personId))
    }
    def apply(from: Activity, toActivity: Activity, params : RoutingRequestParams) = {
      new RoutingRequest(BeamRouter.nextId, from.getCoord, toActivity.getCoord,
        params)
    }
  }

  def props(beamServices: BeamServices) = Props(classOf[BeamRouter], beamServices)
}