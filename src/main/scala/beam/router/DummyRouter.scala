package beam.router

import akka.actor.Props
import beam.agentsim.core.Modes.BeamMode
import beam.sim.BeamServices
import beam.router.RoutingMessages._
import beam.router.RoutingModel.{BeamGraphPath, BeamLeg, BeamTrip}
import com.vividsolutions.jts.geom.Coordinate
import org.matsim.api.core.v01.Coord
import org.matsim.api.core.v01.population.Person

class DummyRouter(theAgentsimServices: BeamServices) extends BeamRouter {
  val agentsimServices = theAgentsimServices

  override def receive: Receive = {
    case InitializeRouter =>
      log.info("Initializing Dummy Router")
      agentsimServices.bbox.observeCoord(new Coordinate(-1e12,-1e12))
      agentsimServices.bbox.observeCoord(new Coordinate(1e12,1e12))
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
  def props(agentsimServices: BeamServices) = Props(classOf[DummyRouter],agentsimServices)
}