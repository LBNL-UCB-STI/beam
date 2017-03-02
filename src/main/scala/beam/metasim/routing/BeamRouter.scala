package beam.metasim.routing

import akka.actor.Actor
import beam.metasim.agents.PersonAgent
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Activity
import org.matsim.core.router.ActivityWrapperFacility
import org.matsim.facilities.Facility

/**
  * Created by sfeygin on 2/28/17.
  */
trait BeamRouter extends Actor  {


  case class RoutingRequest(fromFacility: Facility[_ <: Facility[_]], toFacility: Facility[_ <: Facility[_]], departureTime: Double, personId: Id[PersonAgent])

  object RoutingRequest {
    def apply(fromActivity: Activity, toActivity: Activity, departureTime: Double, personId: Id[PersonAgent]): RoutingRequest = {
      new RoutingRequest(new ActivityWrapperFacility(fromActivity), new ActivityWrapperFacility(toActivity), departureTime, personId)
    }
  }


}

