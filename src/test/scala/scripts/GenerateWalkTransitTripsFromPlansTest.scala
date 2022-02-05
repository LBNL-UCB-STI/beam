package scripts

import beam.router.Modes.BeamMode
import org.matsim.api.core.v01.Coord
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scripts.GenerateWalkTransitTripsFromPlans.Trip

class GenerateWalkTransitTripsFromPlansTest extends AnyFunSuite with Matchers {
  test("reading (OD pair -> legMode -> OD pair) trips from plans") {
    val plansPath = "test/test-resources/scripts/generatedPlans_sample.csv"
    val trips: Iterable[Trip] = GenerateWalkTransitTripsFromPlans.readGeneratedPlansTrips(plansPath)

    trips.size should be(36)
    trips.map(_.mode.value).toSet shouldBe Set(BeamMode.WALK_TRANSIT.value, BeamMode.CAR.value)

    val walkTransitTrips = trips.filter(_.mode == BeamMode.WALK_TRANSIT)
    walkTransitTrips shouldBe Seq(
      Trip(
        "160",
        new Coord(286351.929071621, 73710.0446915274),
        new Coord(282006.863519056, 72673.9963047469),
        BeamMode.WALK_TRANSIT,
        31984
      ),
      Trip(
        "156",
        new Coord(286351.929071621, 73710.0446915274),
        new Coord(279120.867493406, 99635.0648424307),
        BeamMode.WALK_TRANSIT,
        55486
      ),
      Trip(
        "156",
        new Coord(279120.867493406, 99635.0648424307),
        new Coord(286351.929071621, 73710.0446915274),
        BeamMode.WALK_TRANSIT,
        92240
      )
    )
  }
}
