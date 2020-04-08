package beam.agentsim.infrastructure.h3

import scala.util.Random

import beam.agentsim.infrastructure.h3.generator.H3PointGenerator
import org.scalatest.{Matchers, WordSpec}

class H3TestSpec extends WordSpec with Matchers {

  "H3.generateEqualDemandH3Indexes" should {

    "throw an exception" when {
      "it is expected more output indexHases than number of coordinates" in {
        val numberOfPoints = Random.nextInt(10) + 1
        val points = H3PointGenerator.buildSetWithFixedSize(numberOfPoints)
        val randomExpectedOutputSize = numberOfPoints + 1

        val thrown = intercept[H3InvalidOutputSizeException] {
          H3.generateEqualDemandH3Indexes(randomExpectedOutputSize, points)
        }
        assertResult(thrown)(H3InvalidOutputSizeException(randomExpectedOutputSize, points.size))
      }
    }

//    "cannot put very spread 21 points in only 3 buckets" in {
//      val allPoints = (1 to 21).map(_ => H3PointFactory.buildPoint).toSet
//      print(s"@@@ AllPoints: [$allPoints]")
//      val result = H3.generateEqualDemandH3Indexes(3, allPoints)
//      assertResult(true)(result.size > 3)
//    }


  }

//  "H3.generateBuckets" should {
//    "very close neighbours points return in two different cells with higher resolution" in {
//      val a = H3Point(30.2813, -97.7479)
//      val b = H3Point(30.1975, -97.7708)
//      val result: Seq[H3Bucket] = H3.generateBuckets(2, Set(a, b))
//      def containsPoint(value: H3Point) = {
//        result.exists {
//          case H3BucketOfPoints(index, points) => points.contains(value) && index.resolution > H3.MinResolution
//          case _                               => false
//        }
//      }
//      assertResult(2)(result.size)
//      assertResult(true)(containsPoint(a))
//      assertResult(true)(containsPoint(b))
//    }
//  }

}
