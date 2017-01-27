package beam.metasim.playground.sid.agents

import java.util

import akka.actor.Actor
import beam.metasim.playground.sid.agents.RoutingAgent._
import beam.playground.metasim.services.location.{BeamRouterImpl, RouteInformationElement}
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.population.Person
import org.matsim.core.trafficmonitoring.TravelTimeCalculator
import org.matsim.facilities.Facility

import scala.collection.JavaConversions.asScalaBuffer


/**
  * Listens for routing requests from other agents and returns shortest path.
  *
  * Created by sfeygin on 1/27/17.
  */

// Currently routes requests to BeamRouter. It will be better to rewrite this class, IMO. saf, Jan'17
object RoutingAgent {

  sealed trait RoutingResponse

  trait RouteRequest[T]

  case class LinkRouteRequest(from: Link, to: Link, depTime: Double, person: Person) extends RouteRequest[Link]

  case class FacilityRouteRequest(from: Facility[_ <: Facility[_]], to: Facility[_ <: Facility[_]], depTime: Double, person: Person) extends RouteRequest[Facility[_ <: Facility[_]]]

  //TODO: allow getting graph from router? Maybe to ensure object is not passed around, only permit certain requests
//  trait GraphRequest

  case object Unreachable extends RoutingResponse

  case class Route(roads: Seq[RouteInformationElement]) extends RoutingResponse

  // Static method for conversion
  def fromRoadsToRoute(roads: util.LinkedList[RouteInformationElement]): RoutingResponse = Route(asScalaBuffer(roads))

  //  def fromRoadsToRoute(roads: util.List[_<:PlanElement]):RoutingResponse=Route(asScalaBuffer(roads))
}

abstract class RoutingAgent extends Actor {
  // Do we need any other types of routers?
}

/**
  *
  * @param ttc TravelTimeCalculator
  *   {@see TravelTimeCalculator}
  */
// TODO: TTC should be initialized in
class OTPRoutingAgent(ttc: TravelTimeCalculator) extends RoutingAgent {

  val beamRouter = new BeamRouterImpl(ttc)

  override def receive: Receive = {
    case LinkRouteRequest(from, to, depTime, person) => sender ! fromRoadsToRoute(beamRouter.calcRoute(from, to, depTime, person))
    case FacilityRouteRequest(from, to, depTime, person) =>
    // FIXME: fix in BeamRouterImpl
    //     sender ! fromRoadsToRoute(beamRouter.calcRoute(from,to,depTime,person)
//    case GraphRequest => sender ! beamRouter.graph.buildGraph
  }
}


