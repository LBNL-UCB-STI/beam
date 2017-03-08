package beam.agentsim.routing

import java.util
import java.util.List

import akka.actor.Props
import beam.agentsim.routing.DummyRouter.RoutingResponse
import beam.agentsim.sim.AgentsimServices
import org.matsim.api.core.v01.population.{Person, PlanElement}
import org.matsim.core.router.{RoutingModule, StageActivityTypes, TripRouter}
import org.matsim.facilities.Facility

/**
  * Created by sfeygin on 2/28/17.
  */
class DummyRouter(agentsimServices: AgentsimServices, val tripRouter: TripRouter) extends RoutingModule with BeamRouter {

  def calcRoute(fromFacility: Facility[_], toFacility: Facility[_], departureTime: Double, person: Person): List[_ <: PlanElement] = {
    new util.LinkedList[PlanElement]()
  }

  override def receive: Receive = {
    case RoutingRequest(fromFacility, toFacility, departureTime, personId) =>
      val person: Person = agentsimServices.matsimServices.getScenario.getPopulation.getPersons.get(personId)
      sender() ! RoutingResponse(calcRoute(fromFacility, toFacility, departureTime, person))
  }

  override def getStageActivityTypes: StageActivityTypes = new StageActivityTypes {
    override def isStageActivity(activityType: String): Boolean = true
  }

}

object DummyRouter {
  def props(agentsimServices: AgentsimServices,tripRouter: TripRouter) = Props(classOf[DummyRouter],agentsimServices,tripRouter)

  case class RoutingResponse(legs: util.LinkedList[PlanElement])

}