package beam.agentsim.infrastructure.h3

import beam.agentsim.infrastructure.h3.generator.H3PointGenerator
import collection.JavaConverters._
import scala.collection.mutable

import org.scalatest.{Matchers, WordSpec}
//import scala.jdk.CollectionConverters._

class BucketBreakdownExecutorPlanSpec extends WordSpec with Matchers {

  "H3.generateEqualDemandH3Indexes" can {
    "can put 21 close points in only 3 buckets" in {
      // 81447ffffffffff
//      println(H3Wrapper.h3Core.h3GetResolution("81447ffffffffff"))
//      println(H3Wrapper.h3Core.h3ToParentAddress("81447ffffffffff", 0))
//      val tmp: mutable.Seq[String] = H3Wrapper.h3Core.h3ToChildren("81447ffffffffff", 3).asScala
//      println(tmp)
      //h3Core.h3ToParent()
      val latitudeRange = Range.inclusive(30, 35)
      val longitudeRange = Range.inclusive(-97, -90)
      val allPoints: Set[H3Point] = H3PointGenerator.buildSetWithFixedSize(1000, latitudeRange, longitudeRange)

      val content = H3Content.fromPoints(allPoints, H3Content.MinResolution)
      H3Util.writeToShapeFile("before.shp", content)

      val expectedOutputSize = 20
      val breakdownExecutor = BucketBreakdownExecutor(content, expectedOutputSize)

      val result = breakdownExecutor.execute()
      val cleaned = new H3ContentOverlapCleaner(result).execute()

      H3Util.writeToShapeFile("after.shp", cleaned)
    }
  }

  /*
      // [81447ffffffffff(res:1)] [82446ffffffffff(res:2)] [83446dfffffffff(res:3)] [84446d1ffffffff(res:4)] [85446d1bfffffff(res:5)]
      //      H3Point(30.341952986508094, -96.0), // THIS (resolution 3)
      // [8148bffffffffff(res:1)] [82446ffffffffff(res:2)] [83446dfffffffff(res:3)] [84446ddffffffff(res:4)] [85446dd7fffffff(res:5)]
 */

}
