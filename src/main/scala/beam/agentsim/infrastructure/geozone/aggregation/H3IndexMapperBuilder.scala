package beam.agentsim.infrastructure.geozone.aggregation

import scala.collection.parallel.ParSet

import beam.agentsim.infrastructure.geozone.{H3Index, H3IndexMapper, TopDownEqualDemandH3IndexMapper, WgsCoordinate}

trait H3IndexMapperBuilder {
  def buildMapper(wgsCoordinates: ParSet[WgsCoordinate]): H3IndexMapper
}

object H3IndexMapperBuilder {

  def topDownEqualDemand(
    bucketsReduceFactor: Double,
    initialResolution: Int = 1
  ): H3IndexMapperBuilder = {
    new TopDownEqualDemandBuilder(
      bucketsFactor = bucketsReduceFactor,
      initialResolution = initialResolution
    )
  }

  def wgsCoordinate(targetIndexes: ParSet[H3Index]): H3IndexMapperBuilder = {
    new H3ZoneDirectMapperBuilder(targetIndexes)
  }

  class TopDownEqualDemandBuilder(
    bucketsFactor: Double,
    initialResolution: Int
  ) extends H3IndexMapperBuilder {

    override def buildMapper(coordinates: ParSet[WgsCoordinate]): H3IndexMapper = {
      val expectedNumberOfBuckets = Math.max((coordinates.size * bucketsFactor).toInt, 1)
      TopDownEqualDemandH3IndexMapper.from(coordinates, expectedNumberOfBuckets, initialResolution)
    }
  }

  class H3ZoneDirectMapperBuilder(
    targetIndexes: ParSet[H3Index]
  ) extends H3IndexMapperBuilder {

    override def buildMapper(wgsCoordinates: ParSet[WgsCoordinate]): H3IndexMapper = {
      new WgsCoordinateH3IndexMapper(wgsCoordinates, targetIndexes)
    }
  }

}
