package beam.router

import akka.actor.{Actor, ActorLogging}
import beam.agentsim.agents.PersonAgent
import beam.router.BeamRouter.{InitializeRouter, RouterInitialized, RoutingRequest, RoutingResponse}
import beam.router.Modes.BeamMode
import beam.router.RoutingModel.{BeamTime, BeamTrip}
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.{Activity, Person}
import org.matsim.core.router.ActivityWrapperFacility
import org.matsim.facilities.Facility

trait BeamRouter extends Actor with ActorLogging {
  override def receive: Receive = {
    case InitializeRouter =>
      log.info("Initializing Router")
      init
      sender() ! RouterInitialized
    case RoutingRequest(fromFacility, toFacility, departureTime, modes, personId) =>
      //      log.info(s"Router received routing request from person $personId ($sender)")
      sender() ! calcRoute(fromFacility, toFacility, departureTime, modes, getPerson(personId))
    case msg =>
      log.info(s"Unknown message received by Router $msg")
  }

  def init = {
    loadMap
  }

  def loadMap

  def buildRequest(fromFacility: Facility[_], toFacility: Facility[_], departureTime: BeamTime, modes: Vector[BeamMode]) : Any

  def calcRoute(fromFacility: Facility[_], toFacility: Facility[_], departureTime: BeamTime, modes: Vector[BeamMode], person: Person): RoutingResponse

  def getPerson(personId: Id[PersonAgent]): Person
}

object BeamRouter {
  case object InitializeRouter
  case object RouterInitialized

  case class RoutingRequest(fromFacility: Facility[_ <: Facility[_]], toFacility: Facility[_ <: Facility[_]], departureTime: BeamTime, accessMode: Vector[BeamMode], personId: Id[PersonAgent] )

  object RoutingRequest {
    def apply(fromActivity: Activity, toActivity: Activity, departureTime: BeamTime, accessMode: Vector[BeamMode], personId: Id[PersonAgent]): RoutingRequest = {
      new RoutingRequest(new ActivityWrapperFacility(fromActivity), new ActivityWrapperFacility(toActivity), departureTime, accessMode, personId)
    }
  }

  case class RoutingResponse(itinerary: Vector[BeamTrip])
}

