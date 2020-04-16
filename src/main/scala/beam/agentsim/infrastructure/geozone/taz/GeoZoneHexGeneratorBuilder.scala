package beam.agentsim.infrastructure.geozone.taz

import scala.collection.parallel.ParSet

import beam.agentsim.infrastructure.geozone.{GeoZoneHexGenerator, TopDownEqualDemandsGeoZoneHexGenerator, WgsCoordinate}

trait GeoZoneHexGeneratorBuilder {
  def buildHexZoneGenerator(wgsCoordinates: ParSet[WgsCoordinate]): GeoZoneHexGenerator
}

class TopDownEqualDemandsBuilder(
  bucketsFactor: Double,
  initialResolution: Int
) extends GeoZoneHexGeneratorBuilder {
  override def buildHexZoneGenerator(coordinates: ParSet[WgsCoordinate]): GeoZoneHexGenerator = {
    val expectedNumberOfBuckets = Math.max((coordinates.size * bucketsFactor).toInt, 1)
    TopDownEqualDemandsGeoZoneHexGenerator.from(coordinates, expectedNumberOfBuckets, initialResolution)
  }
}

object GeoZoneHexGeneratorBuilder {

  def topDownEqualDemands(
    bucketsReduceFactor: Double,
    initialResolution: Int = 1
  ): GeoZoneHexGeneratorBuilder = {
    new TopDownEqualDemandsBuilder(
      bucketsFactor = bucketsReduceFactor,
      initialResolution = initialResolution
    )
  }
}
