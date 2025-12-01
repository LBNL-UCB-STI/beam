package scripts

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.Inspectors.forAll
import scripts.TravelTimeAndDistanceCalculatorApp.InputParameters

import java.nio.file.Paths

class TravelTimeAndDistanceCalculatorAppSpec extends AnyWordSpecLike with Matchers {

  val parameters = InputParameters(
    configPath = Paths.get("test/input/beamville/beam.conf"),
    linkstatsPath = Paths.get("test/test-resources/beam/router/0.linkstats.csv.gz"),
    router = "R5",
    input = Paths.get("test/test-resources/beam/router/input.csv"),
    output = Paths.get("output.csv")
  )

  "TravelTimeAndDistanceCalculator" should {

    "Run with R5 router" in {
      val app = new TravelTimeAndDistanceCalculatorApp(parameters)
      val results = app.processCsv()

      results.map(_.travelTime) shouldBe Vector(72, 427, 426)

      val actualDistances = results.map(_.distance)
      val expectedDistances = Vector(1127.997, 6679.088, 6667.975)

      withClue(s"Distances do not match expected values. Actual values are $actualDistances") {
        forAll(actualDistances.zip(expectedDistances)) { case (actual, expected) =>
          actual shouldEqual expected +- 1e-6
        }
      }
    }
  }
}
