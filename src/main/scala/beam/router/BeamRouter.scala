package beam.router

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Stash, Terminated}
import akka.routing.{ActorRefRoutee, RoundRobinRoutingLogic, Router}
import beam.agentsim.agents.PersonAgent
import beam.router.Modes.BeamMode
import beam.router.RoutingModel.{BeamTime, BeamTrip}
import beam.router.BeamRouter.{InitializeRouter, RouterInitialized, RoutingRequest}
import beam.sim.BeamServices
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Activity
import org.matsim.core.router.ActivityWrapperFacility
import org.matsim.facilities.Facility

class BeamRouter(beamServices: BeamServices) extends Actor with Stash with ActorLogging {
  var services: BeamServices = beamServices
  var router = Router(RoundRobinRoutingLogic(), Vector.fill(5) {
    ActorRefRoutee(createAndWatch)
  })

  def receive = {

    case InitializeRouter =>
      log.info("Initializing Router.")
      router.route(InitializeRouter, sender())
      context.become({
        case RouterInitialized =>
          unstashAll()
          context.become({
            case w: RoutingRequest =>
              router.route(w, sender())
            case InitializeRouter =>
              log.info("Router already initialized.")
            case Terminated(r) =>
              handelTermination(r)
          })
        case InitializeRouter =>
          log.info("Router initialization in-progress...")
        case RoutingRequest =>
          stash()
        case Terminated(r) =>
          handelTermination(r)
      })
    case RoutingRequest =>
      stash()
    case Terminated(r) =>
      handelTermination(r)
    case msg =>
      log.info(s"Unknown message received by Router $msg")
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
  sealed trait RouterMessage
  case object InitializeRouter extends RouterMessage
  case object RouterInitialized extends RouterMessage

  case class RoutingRequest(fromFacility: Facility[_ <: Facility[_]], toFacility: Facility[_ <: Facility[_]],
                            departureTime: BeamTime, accessMode: Vector[BeamMode], personId: Id[PersonAgent],
                            considerTransit: Boolean = false) extends RouterMessage
  case class RoutingResponse(itinerary: Vector[BeamTrip]) extends RouterMessage

  object RoutingRequest {
    def apply(fromActivity: Activity, toActivity: Activity, departureTime: BeamTime, accessMode: Vector[BeamMode], personId: Id[PersonAgent]): RoutingRequest = {
      new RoutingRequest(new ActivityWrapperFacility(fromActivity), new ActivityWrapperFacility(toActivity), departureTime, accessMode, personId)
    }
  }

  def props(beamServices: BeamServices) = Props(classOf[BeamRouter], beamServices)
}