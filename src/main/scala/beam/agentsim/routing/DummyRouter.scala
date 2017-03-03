package beam.metasim.routing

import java.util

import beam.agentsim.sim.AgentsimServices
import org.matsim.api.core.v01.TransportMode
import org.matsim.api.core.v01.population._
import org.matsim.core.router.TripRouter
import org.matsim.facilities.Facility

/**
  * Created by sfeygin on 2/28/17.
  */
class DummyRouter (agentsimServices: AgentsimServices,val tripRouter: TripRouter) extends BeamRouter {

  override def receive: Receive = {
    case RoutingRequest(fromFacility, toFacility, departureTime, personId) =>
      val person: Person = agentsimServices.matsimServices.getScenario.getPopulation.getPersons.get(personId)
      sender() ! calcRoute(fromFacility, toFacility, departureTime, person)
  }

  def calcRoute(fromFacility: Facility[_ <: Facility[_]], toFacility: Facility[_ <: Facility[_]], departureTime: Double, person: Person): util.List[_ <: PlanElement] = tripRouter.calcRoute(TransportMode.car, fromFacility, toFacility, departureTime, person)
}