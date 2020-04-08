package beam.agentsim.infrastructure.h3

import com.typesafe.scalalogging.LazyLogging

object H3 extends LazyLogging {

  def generateEqualDemandH3Indexes(outputSize: Int, coordinates: Set[H3Point]): Set[H3Index] = {
    val content = H3Content.fromPoints(coordinates, H3Content.MinResolution)
    val breakdownExecutor = BucketBreakdownExecutor(content, outputSize)
    breakdownExecutor.execute().indexes
  }

}
