package beam.utils

import beam.utils.data.ctpp.JointDistribution
import beam.utils.data.ctpp.JointDistribution.CustomRange
import org.apache.commons.math3.random.MersenneTwister
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.matchers.should.Matchers

class JointDistributionTest extends AnyWordSpecLike with Matchers {

  val jointDistribution: JointDistribution = JointDistribution.fromCsvFile(
    pathToCsv = "test/input/beamville/test-data/joint-distribution.csv",
    new MersenneTwister(42),
    columnMapping = Map(
      "age"            -> JointDistribution.DOUBLE_COLUMN_TYPE,
      "startTimeIndex" -> JointDistribution.RANGE_COLUMN_TYPE,
      "durationIndex"  -> JointDistribution.RANGE_COLUMN_TYPE,
      "probability"    -> JointDistribution.DOUBLE_COLUMN_TYPE
    )
  )

  "JointDistributionReader" should {
    "be able to read csv records file" in {
      jointDistribution
        .getProbabilityList(("startTimeIndex", Left("4.00, 4.50")), ("durationIndex", Left("0.0, 2.5")))
        .length shouldBe 20
    }

    "be able to filter with fixed column values" in {
      jointDistribution.getProbabilityList(("age", Left("45"))).length shouldBe 37
    }

    "be able to filter with fixed column and range column values" in {
      jointDistribution
        .getProbabilityList(("age", Left("45")), ("startTimeIndex", Left("4.00, 4.50")))
        .length shouldBe 2
    }

    "be able to sum of probabilities" in {
      assert(
        ~=(
          jointDistribution.getProbability(("startTimeIndex", Left("4.00, 4.50")), ("durationIndex", Left("0.0, 2.5"))),
          9.869863907610778E-8,
          0.0000000001
        )
      )
    }

    "sample test " in {
      val sample = jointDistribution.getSample(false, ("age", Left("22")), ("durationIndex", Left("0.0, 2.5")))
      sample.size shouldBe 4
    }

    "range sample test" in {
      val sample =
        jointDistribution.getSample(false, ("age", Left("22")), ("durationIndex", Right(CustomRange(0.0, 2.5))))
      sample.size shouldBe 4
    }
  }

  def ~=(x: Double, y: Double, precision: Double): Boolean = {
    if ((x - y).abs < precision) true else false
  }
}
//pass value as set
