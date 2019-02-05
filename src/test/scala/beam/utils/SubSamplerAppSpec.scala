package beam.utils

import beam.utils.plan.SubSamplerApp._
import org.scalatest.{Matchers, WordSpecLike}

class SubSamplerAppSpec extends WordSpecLike with Matchers {

  "SubSamplerApp " must {
    "stratified sample average < random average" ignore {
      val sampleDir = "test/input/sf-light/sample/25k"
      val sampleSize = 100

      val srcSc = loadScenario(sampleDir)
      val avgSrc = getAverageCoordinateHouseholds(srcSc)
      val avgSimple = getAverageCoordinateHouseholds(samplePopulation(srcSc, SIMPLE_RANDOM_SAMPLING, sampleSize))
      val avgStratified = getAverageCoordinateHouseholds(samplePopulation(srcSc, STRATIFIED_SAMPLING, sampleSize))

      val distSimple = getDifference(avgSrc, avgSimple)
      val distStratified = getDifference(avgSrc, avgStratified)
      println(distSimple)
      println(distStratified)

      assert(distSimple > distStratified)
    }
  }
}
