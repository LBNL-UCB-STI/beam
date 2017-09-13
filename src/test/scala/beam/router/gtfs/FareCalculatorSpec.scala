package beam.router.gtfs

import java.nio.file.{Path, Paths}

import beam.router.gtfs.FareCalculator._
import org.scalatest.WordSpec

class FareCalculatorSpec extends WordSpec {

  "Fare Calculator" when {
    "not initialized, it" should {
      "load gtfs feed" in{
        var path: Path = Paths.get("out","test","resources","beam","router","gtfs")
        if(!path.toFile.exists())
          path = Paths.get(getClass.getResource(".").toURI)

        fromDirectory(path)

        assert(agencies.nonEmpty)
        assert(agencies.getOrElse("CE", Vector()).nonEmpty)
      }
    }

    var path: Path = Paths.get("out","test","resources","beam","router","gtfs")
    if(!path.toFile.exists())
      path = Paths.get(getClass.getResource(".").toURI)

    fromDirectory(path)

    "calculate fare from 55448 to 55450" should {
      "return 5.5 fare" in {
        assert(calcFare("CE", "ACE", "55448", "55450") == 5.5)
      }
    }

    "calculate fare with null route id, it" should {
      "return proper fare" in {
        assert(calcFare("CE", null, "55448", "55643") == 9.5)
      }
    }

    "calculate fare from 55448 to 55449 against contains_id" should {
      "return 4.5 fare" in {
        assert(calcFare("CE", "ACE", "55448", "55449") == 4.5)
      }
    }

    "calculate fare with null agency id, it" should {
      "return zero fare" in {
        assert(calcFare(null, null, "55448", "55643") == 0.0)
      }
    }

    "calculate fare with wrong route id, it" should {
      "return zero fare" in {
        assert(calcFare("CE", "1", "55448", "55449") == 0.0)
      }
    }

    "getFareSegments with null origin and destination and provided contains" should {
      "return a segment fare" in {
        val sf = getFareSegments("CE", "ACE", null, null, Set("55448", "55449", "55643"))
        assert(sf.nonEmpty)
        assert(sf.size == 1)
        assert(sf.head.fare.fareId == "178841")
        assert(sf.head.fare.price == 13.75)
        assert(sumFares(sf.map(FareSegment(_, 0, 3200))) == sf.head.fare.price)
      }
    }

    "filterTransferFares with four segments" should {
      "return 3 segments within transfer duration" in {
        val fr = getFareSegments("CE", "ACE", null, null, Set("55448", "55449", "55643")).map(FareSegment(_, 0, 3200)) ++
          getFareSegments("CE", "ACE", "55643", "55644").map(FareSegment(_, 0, 3800)) ++
          getFareSegments("CE", "ACE", "55644", "55645").map(FareSegment(_, 0, 4300)) ++
          getFareSegments("CE", "ACE", "55645", "55645").map(FareSegment(_, 0, 4700))
        val fsf = filterTransferFares(fr)
        assert(fsf.nonEmpty)
        assert(fsf.size == 3)
        assert(fsf.head.fare.transferDuration == 3800)
        assert(fsf.map(_.fare.price).sum == 23.0)
      }
    }
  }
}
