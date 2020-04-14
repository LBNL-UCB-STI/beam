package beam.agentsim.infrastructure.geozone

import scala.collection.JavaConverters._
import java.util.{Collections => JCollections}

import beam.agentsim.infrastructure.taz.H3TAZ.{toJtsCoordinate, H3}
import com.uber.h3core.AreaUnit
import org.matsim.api.core.v01.Coord

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

  def hexToCoord(index: GeoIndex): Coord = {
    val coordinate = GeoZoneUtil.toJtsCoordinate(h3Core.h3ToGeo(index.value))
    new Coord(coordinate.x, coordinate.y)
  }

  /** Average hexagon area in square meters at the given resolution.
    * @param resolution Resolution
    */
  def hexAreaM2(resolution: Int): Double = {
    h3Core.hexArea(resolution, AreaUnit.m2)
  }

  private[geozone] val h3Core = com.uber.h3core.H3Core.newInstance

}
