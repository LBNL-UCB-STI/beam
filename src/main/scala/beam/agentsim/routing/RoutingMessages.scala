package beam.agentsim.routing

import beam.agentsim.agents.PersonAgent
import beam.agentsim.routing.RoutingModel.BeamTrip
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Activity
import org.matsim.core.router.ActivityWrapperFacility
import org.matsim.facilities.Facility

/**
  * BEAM
  */
object RoutingMessages {
  case object InitializeRouter
  case object RouterInitialized

  case class RoutingRequest(fromFacility: Facility[_ <: Facility[_]], toFacility: Facility[_ <: Facility[_]], departureTime: Double, personId: Id[PersonAgent])

  object RoutingRequest {
    def apply(fromActivity: Activity, toActivity: Activity, departureTime: Double, personId: Id[PersonAgent]): RoutingRequest = {
      new RoutingRequest(new ActivityWrapperFacility(fromActivity), new ActivityWrapperFacility(toActivity), departureTime, personId)
    }
  }

  case class RoutingResponse(itinerary: Vector[BeamTrip])
}
