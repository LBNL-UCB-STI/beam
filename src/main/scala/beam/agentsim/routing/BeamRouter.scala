package beam.agentsim.routing

import akka.actor.{Actor, ActorLogging}
import beam.agentsim.agents.PersonAgent
import beam.agentsim.routing.RoutingMessages.{InitializeRouter, RouterInitialized, RoutingRequest, RoutingResponse}
import org.geotools.referencing.CRS
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person
import org.matsim.facilities.Facility

/**
  * Created by sfeygin on 2/28/17.
  */
trait BeamRouter extends Actor with ActorLogging {
  override def receive: Receive = {
    case InitializeRouter =>
      log.info("Initializing Router")
      init
      sender() ! RouterInitialized
    case RoutingRequest(fromFacility, toFacility, departureTime, personId) =>
      //      log.info(s"Router received routing request from person $personId ($sender)")
      sender() ! calcRoute(fromFacility, toFacility, departureTime, getPerson(personId))
    case msg =>
      log.info(s"Unknown message received by Router $msg")
  }

  def init = {
    loadMap
  }

  def loadMap

  def buildRequest(fromFacility: Facility[_], toFacility: Facility[_], departureTime: Double, isTransit: Boolean = false) : Any

  def calcRoute(fromFacility: Facility[_], toFacility: Facility[_], departureTime: Double, person: Person): RoutingResponse

  def getPerson(personId: Id[PersonAgent]): Person
}

