package beam.agentsim.infrastructure.parking

import scala.util.Random
import beam.agentsim.infrastructure.taz.TAZ
import org.matsim.api.core.v01.{Coord, Id}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ParkingStallSamplingTestSpec extends AnyWordSpec with Matchers {
  val trialsPerTest: Int = 100
  "testAvailabilityAwareSampling" when {
    "square TAZ with agent destination near corner" when {
      "100% availability" should {
        "place parking stall directly at agent location" in new ParkingStallSamplingTestSpec.SquareTAZWorld {
          val availabilityRatio: Double = 1.0
          val result: Coord = ParkingStallSampling.availabilityAwareSampling(
            random,
            agent,
            taz,
            availabilityRatio,
            true
          )
          distance(agent, result) should equal(0.0)
        }
      }
      ">75% availability" should {
        "place parking stall very close to agent location" in new ParkingStallSamplingTestSpec.SquareTAZWorld {
          for {
            availabilityRatio <- (100 until 75 by -1).map { _.toDouble * 0.01 }
            _                 <- 1 to trialsPerTest
          } {
            val result = ParkingStallSampling.availabilityAwareSampling(
              random,
              agent,
              taz,
              availabilityRatio,
              true
            )

            val dist: Double = distance(agent, result)

            // allow points placed at a distance up to 20% of the taz diameter
            val errorBounds: Double = tazD * 0.20
            dist should be <= errorBounds
          }
        }
      }
      ">50% availability" should {
        "place parking stall in a wider field with relation to the agent" in new ParkingStallSamplingTestSpec.SquareTAZWorld {
          for {
            availabilityRatio <- (75 until 50 by -1).map { _.toDouble * 0.01 }
            _                 <- 1 to trialsPerTest
          } {
            val result = ParkingStallSampling.availabilityAwareSampling(
              random,
              agent,
              taz,
              availabilityRatio,
              true
            )

            val dist: Double = distance(agent, result)

            // allow points placed at a distance up to 50% of the taz diameter
            val errorBounds: Double = tazD * 0.50
            dist should be <= errorBounds
          }
        }
      }
      ">25% availability" should {
        "place parking stall in a range that is nearly as wide as the TAZ with relation to the agent" in new ParkingStallSamplingTestSpec.SquareTAZWorld {
          for {
            availabilityRatio <- (50 until 25 by -1).map { _.toDouble * 0.01 }
            _                 <- 1 to trialsPerTest
          } {
            val result = ParkingStallSampling.availabilityAwareSampling(
              random,
              agent,
              taz,
              availabilityRatio,
              true
            )

            val dist: Double = distance(agent, result)

            // allow points placed at a distance up to 100% of the taz diameter
            val errorBounds: Double = tazD
            dist should be <= errorBounds
          }
        }
      }
      "low availability" should {
        "place 99.9% of parking stalls in a range that is within 125% of TAZ diameter with relation to the TAZ centroid" in new ParkingStallSamplingTestSpec.SquareTAZWorld {
          var (counter, max) = (0, 0)
          for {
            availabilityRatio <- (25 until 0 by -1).map { _.toDouble * 0.01 }
            _                 <- 1 to trialsPerTest
          } {

            val result = ParkingStallSampling.availabilityAwareSampling(
              random,
              agent,
              taz,
              availabilityRatio,
              true
            )

            val dist: Double = distance(taz.coord, result)
            if (dist > tazD * 1.25) counter += 1
            max += 1
          }

          // confirm samples perform within tolerance
          // since we are using gaussian random, we sometimes have outliers, but we require that 99.9% of values are
          // in an expected bounds
          val targetPercentError: Double = 0.001
          val integerScale: Int = 10000
          val ratioAsInteger: Int = math.ceil((counter.toDouble / max.toDouble) * integerScale).toInt
          ratioAsInteger should be <= (targetPercentError * integerScale).toInt
        }
      }
    }
  }

}

object ParkingStallSamplingTestSpec {

  // a parking problem which is a square coordinate system in the range x=[0,1000],y=[0,1000]
  // with agent destination at position (100,100)
  trait SquareTAZWorld {
    val random: Random = new Random(0L)

    val (tazX: Double, tazY: Double) = (500.0, 500.0)
    val tazD: Double = 1000.0
    val tazArea: Double = tazD * tazD

    val taz: TAZ = new TAZ(
      Id.create("taz", classOf[TAZ]),
      new Coord(tazX, tazY),
      tazArea
    )

    val agent: Coord = new Coord(100.0, 100.0)

    // Euclidian distance for tests
    def distance(a: Coord, b: Coord): Double = math.sqrt(math.pow(a.getY - b.getY, 2) + math.pow(a.getX - b.getX, 2))
  }
}
