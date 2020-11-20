package beam.agentsim.infrastructure.geozone

import scala.language.implicitConversions

import beam.sim.common.GeoUtils
import org.matsim.api.core.v01.Coord

case class WgsCoordinate(latitude: Double, longitude: Double) {

  override def toString: String = s"($latitude,$longitude)"

  def coord: Coord = new Coord(longitude, latitude)
}

object WgsCoordinate {

  implicit def apply(wgsCoord: Coord): WgsCoordinate = {
    require(!GeoUtils.isInvalidWgsCoordinate(wgsCoord), s"Provided coordinate $wgsCoord is not in WGS")
    GeoUtils.GeoUtilsWgs.utm2Wgs(wgsCoord)
    WgsCoordinate(latitude = wgsCoord.getY, longitude = wgsCoord.getX)
  }

}
