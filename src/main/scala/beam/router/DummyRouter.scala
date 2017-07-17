package beam.router

import akka.actor.Actor.Receive
import akka.actor.{Actor, ActorLogging, Props}
import beam.router.BeamRouter._
import beam.router.Modes.BeamMode
import beam.router.RoutingModel._
import beam.sim.BeamServices
import com.vividsolutions.jts.geom.Coordinate
import org.matsim.api.core.v01.Coord
import org.matsim.api.core.v01.population.Person

class DummyRouter(val beamServices: BeamServices) extends Actor with ActorLogging {

  override def receive: Receive = {
    case InitializeRouter =>
      log.info("Initializing Dummy Router")
      beamServices.bbox.observeCoord(new Coordinate(-1e12,-1e12))
      beamServices.bbox.observeCoord(new Coordinate(1e12,1e12))
      sender() ! RouterInitialized
    case RoutingRequest(requestId, fromFacility, toFacility, RoutingRequestParams(departureTime, accessMode, personId)) =>
      log.info(s"Serving Route Request from $personId @ $departureTime")
      val person: Person = beamServices.matsimServices.getScenario.getPopulation.getPersons.get(personId)
      val time = departureTime.atTime.toLong
      val dummyWalkStart = BeamLeg.dummyWalk(time)
      val path = BeamGraphPath(Vector[String]("link-" + fromFacility.toString,"link-" + toFacility.toString),
        Vector[Coord](fromFacility,toFacility),
        Vector[Long](time+1,time+101)
      )
      val leg = BeamLeg(time+1, BeamMode.CAR, 100, path)
      val dummyWalkEnd = BeamLeg.dummyWalk(time+101)

      val trip = BeamTrip(Vector[BeamLeg](dummyWalkStart,leg,dummyWalkEnd))
      sender() ! RoutingResponse(requestId, Vector[BeamTrip](trip))
  }

}

object DummyRouter {
  def props(agentsimServices: BeamServices) = Props(classOf[DummyRouter],agentsimServices)
}