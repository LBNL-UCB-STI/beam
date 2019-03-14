package beam.agentsim.infrastructure.parking

import scala.util.Random

import beam.agentsim.infrastructure.taz.TAZ
import org.matsim.api.core.v01.{Coord, Id}
import org.scalatest.{Matchers, WordSpec}

class ParkingStallSamplingTestSpec extends WordSpec with Matchers {

  "testAvailabilityAwareSampling" when {
    "square TAZ" when {
      "100% availability" should {
        "place parking stall directly at agent location" in new ParkingStallSamplingTestSpec.SquareTAZWorld {
          val result: Coord = ParkingStallSampling.availabilityAwareSampling(
            random,
            agent,
            taz,
            availabilityRatio = 1.0
          )
          distance(agent, result) should equal (0.0)
        }
      }
      "90% availability" should {
        "place parking stall very close to agent location" in new ParkingStallSamplingTestSpec.SquareTAZWorld {
          for {
            availabilityRatio <- (100 to 0 by -1).map{ _.toDouble * 0.01 }
            _ <- 1 to 100 // run 100 trials for each availabilityRatio
          } yield {
            val result = ParkingStallSampling.availabilityAwareSampling(
              random,
              agent,
              taz,
              availabilityRatio
            )
            val dist: Double = distance(agent, result)
            println(f"a = $availabilityRatio, dist = $dist%.3f")

            // allow points placed at a distance up to 110% of the taz diameter, scaled by the availability metric
            val errorBounds: Double = (tazD * 1.10) * (1.0 - availabilityRatio)
            dist should be <= errorBounds

            // points should be placed in a bounding box which is 120% of the TAZ area
            result.getX should be >= -100.0
            result.getX should be <= 1100.0
            result.getY should be >= -100.0
            result.getY should be <= 1100.0
          }
        }
      }
    }
  }

}


object ParkingStallSamplingTestSpec {
  trait SquareTAZWorld {
    val random: Random = Random

    val (tazX: Double, tazY: Double) = (500.0, 500.0)
    val tazD: Double = 1000.0
    val tazArea: Double = tazD * tazD

    val taz: TAZ = new TAZ(
      Id.create("taz", classOf[TAZ]),
      new Coord (tazX, tazY),
      tazArea
    )

    val agent: Coord = new Coord(100.0, 100.0)

    def distance(a: Coord, b: Coord): Double = math.sqrt(math.pow(a.getY-b.getY, 2) + math.pow(a.getX-b.getX, 2))
  }
}