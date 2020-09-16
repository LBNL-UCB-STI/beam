package beam.utils.google_routes_db

import beam.utils.mapsapi.googleapi.route.GoogleRoute

package object sql {

  type GeometryPoint = String
  type GeometryLinestring = String

  val projection: Int = 4326

  def makeGeometryPoint(coord: GoogleRoute.Coord): GeometryPoint =
    s"POINT(${coord.lat} ${coord.lng})"

  def makeGeometryLinestring(coords: Seq[GoogleRoute.Coord]): GeometryLinestring = {
    val coordSs = coords.map { coord => s"${coord.lat} ${coord.lng}"}
    s"LINESTRING(${coordSs.mkString(",")})"
  }
}
