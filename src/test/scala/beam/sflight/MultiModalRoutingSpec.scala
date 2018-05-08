package beam.sflight

import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter._
import beam.router.{BeamRouter, Modes, RoutingModel}
import org.matsim.api.core.v01.{Coord, Id}

import scala.language.postfixOps

class MultiModalRoutingSpec extends AbstractSfLightSpec {

  /*
   * IMPORTANT NOTE: This test will fail if there is no GTFS data included in the R5 data directory. Try that first.
   */
  "A multi-modal router" must {
    "return a route with a starting time consistent with profile request" in {
      val origin = new BeamRouter.Location(552788, 4179300) // -122.4007,37.7595
      val destination = new BeamRouter.Location(548918, 4182749) // -122.4444,37.7908
      val time = RoutingModel.WindowTime(100, 200)
      router ! RoutingRequest(origin, destination, time, Vector(), Vector(StreetVehicle(Id.createVehicleId("body-667520-0"), new SpaceTime(new Coord(origin.getX, origin.getY), time.atTime), Modes.BeamMode.WALK, asDriver = true)))
      val response = expectMsgType[RoutingResponse]
      val routedStartTime = response.itineraries.head.beamLegs().head.startTime
      assert(routedStartTime >= 100 && routedStartTime <= 200)
    }
  }
}
