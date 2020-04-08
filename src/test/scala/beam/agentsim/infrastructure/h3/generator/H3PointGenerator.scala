package beam.agentsim.infrastructure.h3.generator

import scala.util.Random

import beam.agentsim.infrastructure.h3.H3Point

object H3PointGenerator {

  def buildPoint: H3Point = buildPointWithRange()

  def buildPointWithRange(
    latitudeRange: Range.Inclusive = Range.inclusive(-90, 90),
    longitudeRange: Range.Inclusive = Range.inclusive(-180, 180)
  ): H3Point = {
    val validLatitude = random(latitudeRange)
    val validLongitude = random(longitudeRange)
    H3Point(validLatitude, validLongitude)
  }

  private def random(range: Range.Inclusive): Double = {
    val position = Random.nextInt(range.size)
    val maybeResult = range(position)
    val coefficient = Random.nextDouble()
    Math.max(range.min.toDouble, Math.min(range.max.toDouble, maybeResult + coefficient))
  }

  def buildSetWithFixedSize(expectedSize: Int): Set[H3Point] = {
    val result = collection.mutable.Set[H3Point]()
    while (result.size < expectedSize) {
      result += buildPoint
    }
    result.toSet
  }

  def buildSetWithFixedSize(
    expectedSize: Int,
    latitudeRange: Range.Inclusive = Range.inclusive(-90, 90),
    longitudeRange: Range.Inclusive = Range.inclusive(-180, 180),
  ): Set[H3Point] = {
    val result = collection.mutable.Set[H3Point]()
    while (result.size < expectedSize) {
      result += buildPointWithRange(latitudeRange, longitudeRange)
    }
    result.toSet
  }

}
