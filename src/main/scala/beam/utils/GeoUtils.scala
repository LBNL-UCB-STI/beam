package beam.utils

import org.matsim.api.core.v01.Coord
import org.matsim.core.utils.geometry.transformations.GeotoolsTransformation


/**
  * Created by sfeygin on 4/2/17.
  */
object GeoUtils {


  object transform{
    //TODO fix this monstrosity
//    private  val utm2Wgs: GeotoolsTransformation = new GeotoolsTransformation("EPSG:26910", "EPSG:4326")
    private  val utm2Wgs: GeotoolsTransformation = new GeotoolsTransformation("EPSG:32631", "EPSG:4326")

    def Utm2Wgs(coord:Coord):Coord={
      //TODO fix this monstrosity
      if (coord.getX > 1.0 | coord.getX < -0.0) {
        utm2Wgs.transform(coord)
      } else {
        coord
      }
    }
  }

  //TODO this is a hack, but we need a general purpose, failsafe way to get distances out of Coords regardless of their project
  def distInMeters(coord1: Coord, coord2: Coord): Double ={
    distLatLon2Meters(transform.Utm2Wgs(coord1), transform.Utm2Wgs(coord2))
  }

  def distLatLon2Meters(coord1: Coord, coord2: Coord): Double = distLatLon2Meters(coord1.getX, coord1.getY, coord2.getX, coord2.getY)

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
