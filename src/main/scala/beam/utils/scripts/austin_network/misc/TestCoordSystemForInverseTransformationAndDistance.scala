package beam.utils.scripts.austin_network.misc

import beam.sim.common.GeoUtils
import beam.utils.scripts.austin_network.AustinUtils
import org.matsim.api.core.v01.Coord

import scala.math.round

object TestCoordSystemForInverseTransformationAndDistance {

  def main(args: Array[String]): Unit = {
    val geoUtils: GeoUtils = new GeoUtils {
      override def localCRS: String = "EPSG:2808"
    }
    val wgsCoord = new Coord(-83.15367924121381, 42.37473505708574)
    val utmCoord = geoUtils.wgs2Utm(wgsCoord)
    val backToWgsCoord = geoUtils.utm2Wgs(utmCoord)

    println(s"wgsCoord: $wgsCoord")
    println(s"backToWgsCoord: $backToWgsCoord")
    println(s"utmCoord: $utmCoord")
    val xDiff = Math.abs(wgsCoord.getX - backToWgsCoord.getX)
    val yDiff = Math.abs(wgsCoord.getY - backToWgsCoord.getY)
    println(s"xDiff: $xDiff")
    println(s"yDiff: $yDiff")
    val googleLink = s"https://www.google.com/maps/dir/${wgsCoord.getY},${wgsCoord.getX}/${backToWgsCoord.getY},${backToWgsCoord.getX}"
    println(googleLink)

    println("===")
    println(s"test distance (compare e.g. to google maps): ${geoUtils.distLatLon2Meters(new Coord(-83.164100, 42.196087), new Coord(-83.819971, 42.577036))}")

  }


}
