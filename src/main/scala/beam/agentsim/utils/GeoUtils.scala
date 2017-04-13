package beam.agentsim.utils

import org.matsim.api.core.v01.Coord
import org.matsim.core.utils.geometry.transformations.GeotoolsTransformation


/**
  * Created by sfeygin on 4/2/17.
  */
object GeoUtils {

  val geoTransform: GeotoolsTransformation = new GeotoolsTransformation("EPSG:26910", "EPSG:4326")

  def transform(coord: Coord): Coord = {
    if (coord.getX > 400.0 | coord.getX < -400.0) {
      geoTransform.transform(coord)
    } else {
      coord
    }
  }

  def distLatLon2Meters(x1: Double, y1: Double, x2: Double, y2: Double): Double = {
    //    http://stackoverflow.com/questions/837872/calculate-distance-in-meters-when-you-know-longitude-and-latitude-in-java
    val earthRadius = 6371000
    val distX = Math.toRadians(x2 - x1)
    val distY = Math.toRadians(y2 - y1)
    val a = Math.sin(distX / 2) * Math.sin(distX / 2) + Math.cos(Math.toRadians(x1)) * Math.cos(Math.toRadians(x2)) * Math.sin(distY / 2) * Math.sin(distY / 2)
    val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    val dist = earthRadius * c
    dist

  }


}
