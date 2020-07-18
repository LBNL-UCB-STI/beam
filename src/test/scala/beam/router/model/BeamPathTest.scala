package beam.router.model

import beam.agentsim.events.SpaceTime
import beam.router.model.RoutingModel.TransitStopsInfo
import org.matsim.api.core.v01.{Coord, Id}
import org.scalatest.{Matchers, WordSpec}

class BeamPathTest extends WordSpec with Matchers {
  "compareSeq" should {
    "return 0" when {
      "both input arrays contain the same elements" in {
        // Check for empty arrays
        BeamPath.compareSeq(Array.empty[Int], Array.empty[Int]) shouldBe 0
        // Non-empty arrays
        BeamPath.compareSeq(Array[Int](1), Array[Int](1)) shouldBe 0
        BeamPath.compareSeq(Array[Int](1, 2), Array[Int](1, 2)) shouldBe 0
      }
    }

    "return 1" when {
      "the first array has more elements than the second" in {
        BeamPath.compareSeq(Array[Int](1), Array[Int]()) shouldBe 1
        BeamPath.compareSeq(Array[Int](1, 2), Array[Int](1)) shouldBe 1
      }

      "the same number of elements but the first array has an element larger than the second array" in {
        BeamPath.compareSeq(Array[Int](2), Array[Int](1)) shouldBe 1
        BeamPath.compareSeq(Array[Int](1, 2), Array[Int](1, 1)) shouldBe 1
      }
    }

    "return -1" when {
      "the first array has less elements than the second" in {
        BeamPath.compareSeq(Array[Int](), Array[Int](1)) shouldBe -1
        BeamPath.compareSeq(Array[Int](1), Array[Int](1, 2)) shouldBe -1
      }

      "the same number of elements but the first array has an element smaller than the second array" in {
        BeamPath.compareSeq(Array[Int](1), Array[Int](2)) shouldBe -1
        BeamPath.compareSeq(Array[Int](1, 1), Array[Int](1, 2)) shouldBe -1
      }
    }
  }

  "compare" should {
    val transitStopsInfo: TransitStopsInfo = TransitStopsInfo("someAgency", "someRoute", Id.createVehicleId(1), 0, 2)
    val beamPath: BeamPath = BeamPath(
      linkIds = Vector(1, 2),
      linkTravelTime = Vector(5, 5),
      transitStops = Some(transitStopsInfo),
      startPoint = SpaceTime(new Coord(0, 0), 0),
      endPoint = SpaceTime(new Coord(2, 2), 5),
      distanceInM = 2.0
    )

    "return 0" when {
      "two BeamPath are equal" in {
        BeamPath.compare(beamPath, beamPath) shouldBe 0
      }
    }

    "return 1" when {
      "the first distance > the second distance" in {
        BeamPath.compare(beamPath.copy(distanceInM = 10), beamPath.copy(distanceInM = 9)) shouldBe 1
      }

      "the first startPoint > the second startPoint" in {
        BeamPath.compare(
          beamPath.copy(startPoint = new SpaceTime(new Coord(1, 1), 0)),
          beamPath.copy(startPoint = new SpaceTime(new Coord(1, 0), 0))
        ) shouldBe 1

        BeamPath.compare(
          beamPath.copy(startPoint = new SpaceTime(new Coord(1, 1), 1)),
          beamPath.copy(startPoint = new SpaceTime(new Coord(1, 1), 0))
        ) shouldBe 1
      }

      "the first endPoint > the second endPoint" in {
        BeamPath.compare(
          BeamPath(
            linkIds = Vector(1, 2),
            linkTravelTime = Vector(5, 5),
            transitStops = Some(transitStopsInfo),
            startPoint = SpaceTime(new Coord(0, 0), 0),
            endPoint = SpaceTime(new Coord(2, 2), 5),
            distanceInM = 2.0
          ),
          BeamPath(
            linkIds = Vector(1, 2),
            linkTravelTime = Vector(5, 5),
            transitStops = Some(transitStopsInfo),
            startPoint = SpaceTime(new Coord(0, 0), 0),
            endPoint = SpaceTime(new Coord(1, 1), 5),
            distanceInM = 2.0
          )
        ) shouldBe 1

        BeamPath.compare(
          BeamPath(
            linkIds = Vector(1, 2),
            linkTravelTime = Vector(10, 10),
            transitStops = Some(transitStopsInfo),
            startPoint = SpaceTime(new Coord(0, 0), 0),
            endPoint = SpaceTime(new Coord(2, 2), 10),
            distanceInM = 2.0
          ),
          BeamPath(
            linkIds = Vector(1, 2),
            linkTravelTime = Vector(5, 5),
            transitStops = Some(transitStopsInfo),
            startPoint = SpaceTime(new Coord(0, 0), 0),
            endPoint = SpaceTime(new Coord(2, 2), 5),
            distanceInM = 2.0
          )
        ) shouldBe 1
      }

      "the first has more elements in linkIds than the second" in {
        BeamPath.compare(beamPath.copy(linkIds = Array(1)), beamPath.copy(linkIds = Array.empty[Int])) shouldBe 1
      }

      "linkIds have the same size, but the first contains element larger than the second one" in {
        BeamPath.compare(beamPath.copy(linkIds = Array(2)), beamPath.copy(linkIds = Array(1))) shouldBe 1
        BeamPath.compare(beamPath.copy(linkIds = Array(1, 2)), beamPath.copy(linkIds = Array(1, 1))) shouldBe 1
      }

      "linkTravelTime have the same size, but the first contains element larger than the second one" in {
        BeamPath.compare(
          beamPath.copy(linkTravelTime = Array(5.0, 5.0)),
          beamPath.copy(linkTravelTime = Array(5.0, 4.0))
        ) shouldBe 1
      }
    }

    "return -1" when {
      "the first distance < the second distance" in {
        BeamPath.compare(beamPath.copy(distanceInM = 9), beamPath.copy(distanceInM = 10)) shouldBe -1
      }

      "the first startPoint < the second startPoint" in {
        BeamPath.compare(
          beamPath.copy(startPoint = new SpaceTime(new Coord(1, 0), 0)),
          beamPath.copy(startPoint = new SpaceTime(new Coord(1, 1), 0))
        ) shouldBe -1

        BeamPath.compare(
          beamPath.copy(startPoint = new SpaceTime(new Coord(1, 1), 0)),
          beamPath.copy(startPoint = new SpaceTime(new Coord(1, 1), 1))
        ) shouldBe -1
      }

      "the first endPoint < the second endPoint" in {
        BeamPath.compare(
          BeamPath(
            linkIds = Vector(1, 2),
            linkTravelTime = Vector(5, 5),
            transitStops = Some(transitStopsInfo),
            startPoint = SpaceTime(new Coord(0, 0), 0),
            endPoint = SpaceTime(new Coord(1, 1), 5),
            distanceInM = 2.0
          ),
          BeamPath(
            linkIds = Vector(1, 2),
            linkTravelTime = Vector(5, 5),
            transitStops = Some(transitStopsInfo),
            startPoint = SpaceTime(new Coord(0, 0), 0),
            endPoint = SpaceTime(new Coord(2, 2), 5),
            distanceInM = 2.0
          )
        ) shouldBe -1

        BeamPath.compare(
          BeamPath(
            linkIds = Vector(1, 2),
            linkTravelTime = Vector(5, 5),
            transitStops = Some(transitStopsInfo),
            startPoint = SpaceTime(new Coord(0, 0), 0),
            endPoint = SpaceTime(new Coord(2, 2), 5),
            distanceInM = 2.0
          ),
          BeamPath(
            linkIds = Vector(1, 2),
            linkTravelTime = Vector(10, 10),
            transitStops = Some(transitStopsInfo),
            startPoint = SpaceTime(new Coord(0, 0), 0),
            endPoint = SpaceTime(new Coord(2, 2), 10),
            distanceInM = 2.0
          )
        ) shouldBe -1
      }

      "the first has less elements in linkIds than the second" in {
        BeamPath.compare(beamPath.copy(linkIds = Array.empty[Int]), beamPath.copy(linkIds = Array(1))) shouldBe -1
      }

      "linkIds have the same size, but the first contains element smaller than the second one" in {
        BeamPath.compare(beamPath.copy(linkIds = Array(1)), beamPath.copy(linkIds = Array(2))) shouldBe -1
        BeamPath.compare(beamPath.copy(linkIds = Array(1, 1)), beamPath.copy(linkIds = Array(1, 2))) shouldBe -1
      }

      "linkTravelTime have the same size, but the first contains element smaller than the second one" in {
        BeamPath.compare(
          beamPath.copy(linkTravelTime = Array(5.0, 4.0)),
          beamPath.copy(linkTravelTime = Array(5.0, 5.0))
        ) shouldBe -1
      }
    }
  }

}
