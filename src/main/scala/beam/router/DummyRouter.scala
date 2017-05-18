package beam.router

import akka.actor.{ActorRef, Props}
import beam.agentsim.agents.BeamAgentScheduler.ScheduleTrigger
import beam.agentsim.agents.PersonAgent
import beam.agentsim.agents.PersonAgent.PersonData
import beam.agentsim.core.Modes.BeamMode
import beam.agentsim.sim.AgentsimServices
import beam.agentsim.sim.AgentsimServices._
import beam.router.BeamRouter.{BeamGraphPath, BeamLeg, BeamTrip}
import beam.router.RoutingMessages._
import com.vividsolutions.jts.geom.Coordinate
import org.matsim.api.core.v01.Coord
import org.matsim.api.core.v01.population.{Activity, Person, PlanElement}

/**
  * Created by sfeygin on 2/28/17.
  */
//TODO tripRouter not being used, remove?
class DummyRouter(agentsimServices: AgentsimServices) extends BeamRouter {
  import beam.agentsim.sim.AgentsimServices._

  override def receive: Receive = {
    case InitializeRouter =>
      log.info("Initializing Dummy Router")
      bbox.observeCoord(new Coordinate(-1e12,-1e12))
      bbox.observeCoord(new Coordinate(1e12,1e12))
      sender() ! RouterInitialized
    case RoutingRequest(fromFacility, toFacility, departureTime, personId) =>
      val person: Person = agentsimServices.matsimServices.getScenario.getPopulation.getPersons.get(personId)

      val dummyWalkStart = BeamLeg.dummyWalk(departureTime.toLong)
      val path = BeamGraphPath(Vector[String](fromFacility.getLinkId.toString,toFacility.getLinkId.toString),
        Vector[Coord](fromFacility.getCoord,toFacility.getCoord),
        Vector[Long](departureTime.toLong+1,departureTime.toLong+101)
      )
      val leg = BeamLeg(departureTime.toLong+1,BeamMode.CAR,100,path)
      val dummyWalkEnd = BeamLeg.dummyWalk(departureTime.toLong+101)
      
      val trip = BeamTrip(Vector[BeamLeg](dummyWalkStart,leg,dummyWalkEnd))
      sender() ! RoutingResponse(Vector[BeamTrip](trip))
  }

}

object DummyRouter {
  //TODO test if this is being used anywhere
  def props(agentsimServices: AgentsimServices) = Props(classOf[DummyRouter],agentsimServices)
}