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
    WgsCoordinate(latitude = wgsCoord.getY, longitude = wgsCoord.getX)
  }

  private val geoUtilsUtm: GeoUtils = new GeoUtils {
    override def localCRS: String = "epsg:26910"
  }

  def fromUtm(longitude: Double, latitude: Double): WgsCoordinate = {
    val utmCoordinate = new Coord(longitude, latitude)
    val wgsCoordinate = geoUtilsUtm.utm2Wgs(utmCoordinate)
    WgsCoordinate(wgsCoordinate)
  }

}
