package beam.router

import akka.actor.{Actor, ActorLogging, Props}
import beam.agentsim.agents.PersonAgent
import beam.router.BeamRouter._
import beam.router.Modes.BeamMode
import beam.router.RoutingModel.{BeamTime, BeamTrip}
import beam.sim.{BeamServices, HasServices}
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.{Activity, Person}
import org.matsim.core.router.ActivityWrapperFacility
import org.matsim.facilities.Facility

trait BeamRouter extends Actor with ActorLogging with HasServices {
  override final def receive: Receive = {
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

  def calcRoute(fromFacility: Facility[_], toFacility: Facility[_], departureTime: BeamTime, accessMode: Vector[BeamMode], person: Person, isTransit: Boolean = false): RoutingResponse

  protected def init = {
    loadMap
  }

  protected def loadMap

  protected def buildRequest(fromFacility: Facility[_], toFacility: Facility[_], departureTime: BeamTime, accessMode: Vector[BeamMode], isTransit: Boolean = false) : Any

  protected def getPerson(personId: Id[PersonAgent]): Person = services.matsimServices.getScenario.getPopulation.getPersons.get(personId)
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

  trait HasProps {
    def props(beamServices: BeamServices): Props
  }
}



