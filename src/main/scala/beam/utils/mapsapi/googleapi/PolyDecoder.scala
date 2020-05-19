package beam.utils.mapsapi.googleapi

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import beam.agentsim.infrastructure.geozone.WgsCoordinate

object PolyDecoder {

  def decode(polyline: String): Seq[WgsCoordinate] = {
    val len: Int = polyline.length
    val path: mutable.ArrayBuffer[WgsCoordinate] = new ArrayBuffer[WgsCoordinate]
    var index: Int = 0
    var lat: Int = 0
    var lng: Int = 0
    while (index < len) {
      var result: Int = 1
      var shift: Int = 0
      var b: Int = 0
      do {
        index += 1
        b = polyline.charAt(index - 1) - 63 - 1
        result += b << shift
        shift += 5
      } while (b >= 0x1f)
      lat += calculateIncrement(result)
      result = 1
      shift = 0
      do {
        index += 1
        b = polyline.charAt(index - 1) - 63 - 1
        result += b << shift
        shift += 5
      } while (b >= 0x1f)
      lng += calculateIncrement(result)
      path.append(new WgsCoordinate(latitude = lat * 1e-5, longitude = lng * 1e-5))
    }
    path.toIndexedSeq
  }

  private def calculateIncrement(result: Int): Int = {
    if ((result & 1) != 0) ~(result >> 1)
    else result >> 1
  }

}
