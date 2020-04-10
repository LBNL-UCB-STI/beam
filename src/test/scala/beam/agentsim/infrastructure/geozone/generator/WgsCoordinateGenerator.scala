package beam.agentsim.infrastructure.geozone.generator

import scala.util.Random

import beam.agentsim.infrastructure.geozone.WgsCoordinate

object WgsCoordinateGenerator {

  def buildPoint: WgsCoordinate = buildPointWithRange()

  def buildPointWithRange(
    latitudeRange: Range.Inclusive = Range.inclusive(-90, 90),
    longitudeRange: Range.Inclusive = Range.inclusive(-180, 180)
  ): WgsCoordinate = {
    val validLatitude = random(latitudeRange)
    val validLongitude = random(longitudeRange)
    WgsCoordinate(validLatitude, validLongitude)
  }

  private def random(range: Range.Inclusive): Double = {
    val position = Random.nextInt(range.size)
    val maybeResult = range(position)
    val coefficient = Random.nextDouble()
    Math.max(range.min.toDouble, Math.min(range.max.toDouble, maybeResult + coefficient))
  }

  def buildSetWithFixedSize(expectedSize: Int): Set[WgsCoordinate] = {
    val result = collection.mutable.Set[WgsCoordinate]()
    while (result.size < expectedSize) {
      result += buildPoint
    }
    result.toSet
  }

  def buildSetWithFixedSize(
    expectedSize: Int,
    latitudeRange: Range.Inclusive = Range.inclusive(-90, 90),
    longitudeRange: Range.Inclusive = Range.inclusive(-180, 180),
  ): Set[WgsCoordinate] = {
    val result = collection.mutable.Set[WgsCoordinate]()
    while (result.size < expectedSize) {
      result += buildPointWithRange(latitudeRange, longitudeRange)
    }
    result.toSet
  }

}
