package beam.router.gtfs

import beam.router.gtfs.FareCalculator._
import beam.sim.BeamHelper
import beam.sim.config.BeamConfig
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike

import scala.language.postfixOps

class FareCalculatorSpec extends AnyWordSpecLike with BeamHelper {

  "Using sf-light calculator" when {
    val config = testConfig("test/input/sf-light/sf-light.conf").resolve()
    val sfLightFareCalc = new FareCalculator(BeamConfig(config))

    "calculate fare for 3555 to 6332" should {
      "return $2.5 fare" in {
        assert(sfLightFareCalc.calcFare("SFMTA", "1033", "3555", "6332") == 2.75)
      }
    }

    "calculate fare for cable car route 1060 from stops 5156 to 5067" should {
      "return $7.0 fare" in {
        assert(sfLightFareCalc.calcFare("SFMTA", "1060", "5156", "5067") == 7.0)
      }
    }

    "calculate fare with null route id, it" should {
      "not return any fare" in {
        assert(sfLightFareCalc.calcFare("SFMTA", null, "3832", "7302") == 0.0)
      }
    }

    "calculate fare with null agency id, it" should {
      "not return any fare" in {
        assert(sfLightFareCalc.calcFare(null, null, "3832", "7302") == 0.0)
      }
    }

    "calculate fare  with null origin and destination and provided contains" should {
      "return a segment fare of $2.75" in {
        assert(
          sfLightFareCalc.calcFare("SFMTA", "1034", null, null, Set("3548", "3887", "7302")) == 2.75
        )
      }
    }
  }

  "Using test Calculator" when {
    val config = ConfigFactory
      .parseString("beam.routing.r5.directory=test/input/fares")
      .withFallback(testConfig("test/input/sf-light/sf-light.conf"))
      .resolve()

    val testFareCalc = new FareCalculator(BeamConfig(config))
    "calculate fare from 55448 to 55450" should {
      "return 5.5 fare" in {
        assert(testFareCalc.calcFare("CE", "ACE", "55448", "55450") == 5.5)
      }
    }

    "calculate fare with null route id, it" should {
      "return proper fare" in {
        assert(testFareCalc.calcFare("CE", null, "55448", "55643") == 9.5)
      }
    }

    "calculate fare from 55448 to 55449 against contains_id" should {
      "return 4.5 fare" in {
        assert(testFareCalc.calcFare("CE", "ACE", "55448", "55449") == 4.5)
      }
    }

    "calculate fare with null agency id, it" should {
      "not return any fare" in {
        assert(testFareCalc.calcFare(null, null, "55448", "55643") == 0.0)
      }
    }

    "calculate fare with wrong route id, it" should {
      "return zero fare" in {
        assert(testFareCalc.calcFare("CE", "1", "55448", "55449") == 0.0)
      }
    }

    "calculate fare  with null origin and destination and provided contains" should {
      "return a segment fare" in {
        assert(
          testFareCalc.calcFare("CE", "ACE", null, null, Set("55448", "55449", "55643")) == 13.75
        )
      }
    }

    "filterTransferFares with four segments" should {
      "return 3 segments within transfer duration" in {
        val fr = testFareCalc
          .getFareSegments("CE", "ACE", null, null, Set("55448", "55449", "55643"))
          .map(BeamFareSegment(_, 0, 3200)) ++
//          testFareCalc.getFareSegments("CE", "ACE", "55643", "55644").map(BeamFareSegment(_, 0, 3800)) ++
        testFareCalc
          .getFareSegments("CE", "ACE", "55644", "55645")
          .map(BeamFareSegment(_, 0, 4300)) ++
        testFareCalc.getFareSegments("CE", "ACE", "55645", "55645").map(BeamFareSegment(_, 0, 4700))
        val fsf = filterFaresOnTransfers(fr)
        assert(fsf.nonEmpty)
        assert(fsf.size == 3)
        assert(fsf.head.fare.transferDuration == 3800)
        assert(fsf.map(_.fare.price).sum == 23.0)
      }
    }
  }
}
