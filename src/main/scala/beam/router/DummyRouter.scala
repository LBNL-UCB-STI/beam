package beam.router

import akka.actor.Actor.Receive
import akka.actor.{Actor, ActorLogging, Props}
import beam.router.BeamRouter.{InitializeRouter, RouterInitialized, RoutingRequest, RoutingResponse}
import beam.router.Modes.BeamMode
import beam.router.RoutingModel.{BeamGraphPath, BeamLeg, BeamTrip, DiscreteTime}
import beam.sim.BeamServices
import com.vividsolutions.jts.geom.Coordinate
import org.matsim.api.core.v01.Coord
import org.matsim.api.core.v01.population.Person

class DummyRouter(theBeamServices: BeamServices) extends Actor with ActorLogging {
  val beamServices = theBeamServices

  override def receive: Receive = {
    case InitializeRouter =>
      log.info("Initializing Dummy Router")
      beamServices.bbox.observeCoord(new Coordinate(-1e12,-1e12))
      beamServices.bbox.observeCoord(new Coordinate(1e12,1e12))
      sender() ! RouterInitialized
    case RoutingRequest(fromFacility, toFacility, departureTime, accessMode, personId, considerTransit) =>
      val person: Person = beamServices.matsimServices.getScenario.getPopulation.getPersons.get(personId)
      val dTime = departureTime.asInstanceOf[DiscreteTime]
      val dummyWalkStart = BeamLeg.dummyWalk(dTime.atTime.toLong)
      val path = BeamGraphPath(Vector[String](fromFacility.getLinkId.toString,toFacility.getLinkId.toString),
        Vector[Coord](fromFacility.getCoord,toFacility.getCoord),
        Vector[Long](dTime.atTime.toLong+1,dTime.atTime.toLong+101)
      )
      val leg = BeamLeg(dTime.atTime.toLong+1, BeamMode.CAR, 100, path)
      val dummyWalkEnd = BeamLeg.dummyWalk(dTime.atTime.toLong+101)

      val trip = BeamTrip(Vector[BeamLeg](dummyWalkStart,leg,dummyWalkEnd))
      sender() ! RoutingResponse(Vector[BeamTrip](trip))
  }

}

object DummyRouter {
  def props(agentsimServices: BeamServices) = Props(classOf[DummyRouter],agentsimServices)
}