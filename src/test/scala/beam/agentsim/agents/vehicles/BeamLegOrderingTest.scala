package beam.agentsim.agents.vehicles

import beam.agentsim.events.SpaceTime
import beam.router.Modes.BeamMode
import beam.router.model.{BeamLeg, BeamPath}
import beam.router.model.RoutingModel.TransitStopsInfo
import org.matsim.api.core.v01.{Coord, Id}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class BeamLegOrderingTest extends AnyWordSpec with Matchers {
  val transitStopsInfo: TransitStopsInfo = TransitStopsInfo("someAgency", "someRoute", Id.createVehicleId(1), 0, 2)

  val beamPath: BeamPath = BeamPath(
    linkIds = Vector(1, 2),
    linkTravelTime = Vector(5, 5),
    transitStops = Some(transitStopsInfo),
    startPoint = SpaceTime(new Coord(0, 0), 0),
    endPoint = SpaceTime(new Coord(2, 2), 5),
    distanceInM = 2.0
  )
  val beamLeg: BeamLeg = BeamLeg(startTime = 10, mode = BeamMode.BUS, duration = 200, travelPath = beamPath)

  "compare" should {
    "return 0" when {
      "Beam legs are the same" in {
        BeamLegOrdering.compare(beamLeg, beamLeg) shouldBe 0
      }

      "Beam legs are the same, but modes are different" in {
        // Yes, modes are not considered!
        BeamLegOrdering.compare(beamLeg, beamLeg.copy(mode = BeamMode.CAR)) shouldBe 0
      }
    }

    "return 1" when {
      "the first startTime > the second startTime" in {
        BeamLegOrdering.compare(beamLeg.copy(startTime = 40), beamLeg.copy(startTime = 10)) shouldBe 1
      }

      "the first duration > the second duration" in {
        BeamLegOrdering.compare(beamLeg.copy(duration = 40), beamLeg.copy(duration = 10)) shouldBe 1
      }

      "the first travelPath > the second travelPath" in {
        BeamLegOrdering.compare(
          beamLeg.copy(travelPath = beamLeg.travelPath.copy(distanceInM = 5.0)),
          beamLeg
        ) shouldBe 1
      }
    }

    "return -1" when {
      "the first startTime < the second startTime" in {
        BeamLegOrdering.compare(beamLeg.copy(startTime = 10), beamLeg.copy(startTime = 40)) shouldBe -1
      }

      "the first duration < the second duration" in {
        BeamLegOrdering.compare(beamLeg.copy(duration = 10), beamLeg.copy(duration = 40)) shouldBe -1
      }

      "the first travelPath < >the second travelPath" in {
        BeamLegOrdering.compare(
          beamLeg.copy(travelPath = beamLeg.travelPath.copy(distanceInM = 1.0)),
          beamLeg
        ) shouldBe -1
      }
    }
  }
}
