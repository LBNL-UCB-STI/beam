package beam.router.osm

import java.nio.file.{Path, Paths}

import org.scalatest.WordSpecLike

import scala.language.postfixOps

class TollCalculatorSpec extends WordSpecLike {
  "Using beamville as input" when {
    val beamvillePath: Path = Paths.get("test", "input", "beamville", "r5")
    val beamvilleTollCalc = new TollCalculator(beamvillePath.toString)
    "calsulate toll , it" should {
      "return value 0." in {
        assert(beamvilleTollCalc.calcToll(Vector(1,2)) == 0)
      }
    }
  }
}
