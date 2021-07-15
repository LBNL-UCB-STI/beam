package beam.agentsim.infrastructure.geozone

import scala.collection.JavaConverters._
import java.util.{Collections => JCollections}

import com.uber.h3core.AreaUnit

object H3Wrapper {

  def getResolution(index: String): Int = {
    h3Core.h3GetResolution(index)
  }

  def getIndex(point: WgsCoordinate, resolution: Int): H3Index = {
    H3Index(h3Core.geoToH3Address(point.latitude, point.longitude, resolution))
  }

  def areaInM2(index: H3Index): Double = {
    h3Core.hexArea(index.resolution, AreaUnit.m2)
  }

  def geoToH3Address(point: WgsCoordinate, resolution: Int): String = {
    h3Core.geoToH3Address(point.latitude, point.longitude, resolution)
  }

  def round(value: Double, places: Int): Double = {
    BigDecimal.valueOf(value).setScale(places, BigDecimal.RoundingMode.HALF_UP).doubleValue()
  }

  def getChildren(index: H3Index): Set[H3Index] = {
    getChildren(index, index.resolution + 1)
  }

  def getChildren(index: H3Index, resolution: Int): Set[H3Index] = {
    h3Core.h3ToChildren(index.value, Math.min(resolution, 15)).asScala.toSet.map(H3Index.apply)
  }

  def internalIndexes(rectangle: WgsRectangle, resolution: Int): Set[H3Index] = {
    h3Core
      .polyfillAddress(rectangle.asGeoBoundary, JCollections.emptyList(), resolution)
      .asScala
      .map(H3Index.apply)
      .toSet
  }

  /**
    * Average hexagon area in square meters at the given resolution.
    * @param resolution Resolution
    */
  def hexAreaM2(resolution: Int): Double = {
    h3Core.hexArea(resolution, AreaUnit.m2)
  }

  def wgsCoordinate(index: H3Index): WgsCoordinate = {
    val coord = h3Core.h3ToGeo(index.value)
    WgsCoordinate(latitude = coord.lat, longitude = coord.lng)
  }

  private[geozone] val h3Core = com.uber.h3core.H3Core.newInstance

}
