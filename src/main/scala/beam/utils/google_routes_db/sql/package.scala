package beam.utils.google_routes_db

import com.google.maps.model.LatLng

package object sql {

  type GeometryPoint = String
  type GeometryLinestring = String

  val projection: Int = 4326

  def makeGeometryPoint(coord: LatLng): GeometryPoint =
    s"POINT(${coord.lat} ${coord.lng})"

  def makeGeometryLinestring(coords: Seq[LatLng]): GeometryLinestring = {
    val coordSs = coords.map { coord =>
      s"${coord.lat} ${coord.lng}"
    }
    s"LINESTRING(${coordSs.mkString(",")})"
  }
}
