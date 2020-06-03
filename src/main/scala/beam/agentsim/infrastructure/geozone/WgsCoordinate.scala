package beam.agentsim.infrastructure.geozone

import beam.sim.common.GeoUtils
import org.matsim.api.core.v01.Coord

case class WgsCoordinate(latitude: Double, longitude: Double) {

  override def toString: String = s"($latitude,$longitude)"
  def coord: Coord = new Coord(longitude, latitude)

}

object WgsCoordinate {

  def apply(wgsCoord: Coord): WgsCoordinate = {
    require(!GeoUtils.isInvalidWgsCoordinate(wgsCoord), s"Provided coordinate $wgsCoord is not in WGS")
    WgsCoordinate(latitude = wgsCoord.getY, longitude = wgsCoord.getX)
  }
}
