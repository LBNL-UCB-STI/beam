package beam.router

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

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
      results.map(_.distance) shouldBe Vector(1127.997, 6679.088000000001, 6667.975000000001)
    }

  }
}
