package beam.agentsim.infrastructure.geozone.aggregation

import scala.collection.parallel.ParSet

import beam.agentsim.infrastructure.geozone.{GeoIndex, GeoIndexMapper, TopDownEqualDemandsGeoIndexMapper, WgsCoordinate}

trait GeoIndexMapperBuilder {
  def buildMapper(wgsCoordinates: ParSet[WgsCoordinate]): GeoIndexMapper
}

object GeoIndexMapperBuilder {

  def topDownEqualDemands(
    bucketsReduceFactor: Double,
    initialResolution: Int = 1
  ): GeoIndexMapperBuilder = {
    new TopDownEqualDemandsBuilder(
      bucketsFactor = bucketsReduceFactor,
      initialResolution = initialResolution
    )
  }

  def wgsCoordinate(targetIndexes: ParSet[GeoIndex]): GeoIndexMapperBuilder = {
    new GeoZoneDirectMapperBuilder(targetIndexes)
  }

  class TopDownEqualDemandsBuilder(
    bucketsFactor: Double,
    initialResolution: Int
  ) extends GeoIndexMapperBuilder {
    override def buildMapper(coordinates: ParSet[WgsCoordinate]): GeoIndexMapper = {
      val expectedNumberOfBuckets = Math.max((coordinates.size * bucketsFactor).toInt, 1)
      TopDownEqualDemandsGeoIndexMapper.from(coordinates, expectedNumberOfBuckets, initialResolution)
    }
  }

  class GeoZoneDirectMapperBuilder(
    targetIndexes: ParSet[GeoIndex]
  ) extends GeoIndexMapperBuilder {
    override def buildMapper(wgsCoordinates: ParSet[WgsCoordinate]): GeoIndexMapper = {
      new WgsCoordinateGeoIndexMapper(wgsCoordinates, targetIndexes)
    }
  }

}
