package beam.agentsim.infrastructure.geozone

import java.util.{List => JList}
import java.util

import scala.collection.parallel.ParSet

import com.uber.h3core.util.GeoCoord

case class WgsRectangle(
  topLeft: WgsCoordinate,
  topRight: WgsCoordinate,
  bottomRight: WgsCoordinate,
  bottomLeft: WgsCoordinate
) {
  private[geozone] def asGeoBoundary: JList[GeoCoord] = {
    util.Arrays.asList(
      new GeoCoord(topLeft.latitude, topLeft.longitude),
      new GeoCoord(topRight.latitude, topRight.longitude),
      new GeoCoord(bottomRight.latitude, bottomRight.longitude),
      new GeoCoord(bottomLeft.latitude, bottomLeft.longitude),
    )
  }
  def coordinates: Set[WgsCoordinate] = Set(topLeft, topRight, bottomRight, bottomLeft)
}

object WgsRectangle {

  def from(coordinates: Set[WgsCoordinate]): WgsRectangle = from(coordinates.par)

  def from(parallel: ParSet[WgsCoordinate]): WgsRectangle = {
    val (latMin, latMax) = {
      val latitudes = parallel.map(_.latitude)
      (latitudes.min, latitudes.max)
    }
    val (longMin, longMax) = {
      val longitudes = parallel.map(_.longitude)
      (longitudes.min, longitudes.max)
    }
    WgsRectangle(
      topLeft = WgsCoordinate(latitude = latMax, longitude = longMin),
      topRight = WgsCoordinate(latitude = latMax, longitude = longMax),
      bottomRight = WgsCoordinate(latitude = latMin, longitude = longMax),
      bottomLeft = WgsCoordinate(latitude = latMin, longitude = longMin),
    )
  }

}
