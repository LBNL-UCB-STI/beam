package beam.agentsim.infrastructure.geozone

import scala.collection.JavaConverters._

import java.util.{Collections => JCollections}

object H3Wrapper {

  def getResolution(index: String): Int = {
    h3Core.h3GetResolution(index)
  }

  def getIndex(point: WgsCoordinate, resolution: Int): GeoIndex = {
    GeoIndex(h3Core.geoToH3Address(point.latitude, point.longitude, resolution))
  }

  def geoToH3Address(point: WgsCoordinate, resolution: Int): String = {
    h3Core.geoToH3Address(point.latitude, point.longitude, resolution)
  }

  def round(value: Double, places: Int): Double = {
    BigDecimal.valueOf(value).setScale(places, BigDecimal.RoundingMode.HALF_UP).doubleValue()
  }

  def getChildren(index: GeoIndex): Set[GeoIndex] = {
    getChildren(index, index.resolution + 1)
  }

  def getChildren(index: GeoIndex, resolution: Int): Set[GeoIndex] = {
    h3Core.h3ToChildren(index.value, Math.min(resolution, 15)).asScala.toSet.map(GeoIndex.apply)
  }

  def internalIndexes(rectangle: WgsRectangle, resolution: Int): Set[GeoIndex] = {
    h3Core
      .polyfillAddress(rectangle.asGeoBoundary, JCollections.emptyList(), resolution)
      .asScala
      .map(GeoIndex.apply)
      .toSet
  }

  private[geozone] val h3Core = com.uber.h3core.H3Core.newInstance

}
