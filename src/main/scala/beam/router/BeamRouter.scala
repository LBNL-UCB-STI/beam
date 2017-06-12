package beam.router

import akka.actor.{Actor, ActorLogging}
import Modes.BeamMode
import Modes.BeamMode._
import beam.agentsim.agents.PersonAgent
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.{InitializeRouter, RouterInitialized, RoutingRequest, RoutingResponse}
import beam.router.RoutingModel.{BeamTime, BeamTrip}
import beam.sim.BeamServices
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.api.core.v01.population.{Activity, Person}
import org.matsim.core.router.ActivityWrapperFacility
import org.matsim.facilities.Facility
import org.slf4j.{Logger, LoggerFactory}

trait BeamRouter extends Actor with ActorLogging {
  override def receive: Receive = {
    case InitializeRouter =>
      log.info("Initializing Router")
      init
      sender() ! RouterInitialized
    case RoutingRequest(fromFacility, toFacility, departureTime, accessMode, personId, considerTransit) =>
      //      log.info(s"Router received routing request from person $personId ($sender)")
      sender() ! calcRoute(fromFacility, toFacility, departureTime, accessMode, getPerson(personId), considerTransit)
    case msg =>
      log.info(s"Unknown message received by Router $msg")
  }

  def init = {
    loadMap
  }

  def loadMap

  def buildRequest(fromFacility: Facility[_], toFacility: Facility[_], departureTime: BeamTime, accessMode: Vector[BeamMode], isTransit: Boolean = false) : Any

  def calcRoute(fromFacility: Facility[_], toFacility: Facility[_], departureTime: BeamTime, accessMode: Vector[BeamMode], person: Person, isTransit: Boolean = false): RoutingResponse

  def getPerson(personId: Id[PersonAgent]): Person
}

object BeamRouter {
  case object InitializeRouter
  case object RouterInitialized

  case class RoutingRequest(fromFacility: Facility[_ <: Facility[_]], toFacility: Facility[_ <: Facility[_]], departureTime: BeamTime, accessMode: Vector[BeamMode], personId: Id[PersonAgent], considerTransit: Boolean = false)

  object RoutingRequest {
    def apply(fromActivity: Activity, toActivity: Activity, departureTime: BeamTime, accessMode: Vector[BeamMode], personId: Id[PersonAgent]): RoutingRequest = {
      new RoutingRequest(new ActivityWrapperFacility(fromActivity), new ActivityWrapperFacility(toActivity), departureTime, accessMode, personId)
    }
  }

  case class RoutingResponse(itinerary: Vector[BeamTrip])
}

