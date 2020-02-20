package beam.utils

import beam.utils.data.ctpp.JointDistribution
import beam.utils.data.ctpp.JointDistribution.CustomRange
import org.scalatest.{Matchers, WordSpecLike}

class JointDistributionTest extends WordSpecLike with Matchers {

  val jointDistribution = JointDistribution(
    "test/input/beamville/test-data/joint-distribution.csv",
    Map(
      "age"            -> JointDistribution.DOUBLE_COLUMN_TYPE,
      "startTimeIndex" -> JointDistribution.RANGE_COLUMN_TYPE,
      "durationIndex"  -> JointDistribution.RANGE_COLUMN_TYPE,
      "probability"    -> JointDistribution.DOUBLE_COLUMN_TYPE
    )
  )

  "JointDistributionReader" should {
    "be able to read csv records file" in {
      jointDistribution
        .getProbabilityList(("startTimeIndex", "4.00, 4.50"), ("durationIndex", "0.0, 2.5"))
        .length shouldBe 20
    }

    "be able to filter with fixed column values" in {
      jointDistribution.getProbabilityList(("age", "45")).length shouldBe 37
    }

    "be able to filter with fixed column and range column values" in {
      jointDistribution.getProbabilityList(("age", "45"), ("startTimeIndex", "4.00, 4.50")).length shouldBe 2
    }

    "be able to sum of probabilities" in {
      assert(
        ~=(
          jointDistribution.getSum(("startTimeIndex", "4.00, 4.50"), ("durationIndex", "0.0, 2.5")),
          9.869863907610778E-8,
          0.0000000001
        )
      )
    }

    "be able to sum list of probabilities" in {
      val sum = jointDistribution
        .getProbabilityList(("startTimeIndex", "4.00, 4.50"), ("durationIndex", "0.0, 2.5"))
        .map(_.toDouble)
        .sum

      assert(
        ~=(jointDistribution.getSum(("startTimeIndex", "4.00, 4.50"), ("durationIndex", "0.0, 2.5")), sum, 0.0000000001)
      )
    }

    "sample test " in {
      val sample = jointDistribution.getSample(false, ("age", "22"), ("durationIndex", "0.0, 2.5"))
      println(sample("durationIndex"))
    }

    "range sample test" in {
      val sample = jointDistribution.getRangeSample(false, Map("age" -> "22"), ("durationIndex", CustomRange(0.0, 2.5)))
      println(sample("durationIndex"))
    }
  }

  def ~=(x: Double, y: Double, precision: Double): Boolean = {
    if ((x - y).abs < precision) true else false
  }
}
//pass value as set
