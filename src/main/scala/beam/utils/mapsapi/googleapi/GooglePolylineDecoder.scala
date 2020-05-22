package beam.utils.mapsapi.googleapi

import scala.collection.mutable.ArrayBuffer

import beam.agentsim.infrastructure.geozone.WgsCoordinate

object GooglePolylineDecoder {
  private val accuracy: Double = 1e-5
  private val accuracyDigits: Int = 5
  private val questionMarkPlusOne = 64

  def decode(polyline: String): Seq[WgsCoordinate] = {
    val result = new ArrayBuffer[WgsCoordinate]
    var index: Int = 0
    var lat: Int = 0
    var long: Int = 0
    while (index < polyline.length) {
      val (indexInc1, latitudeInc) = calculateIndexAndCoordinateIncrements(index, polyline)
      index += indexInc1
      lat += latitudeInc

      val (indexInc2, longitudeInc) = calculateIndexAndCoordinateIncrements(index, polyline)
      index += indexInc2
      long += longitudeInc

      val wgsCoordinate = WgsCoordinate(latitude = lat * accuracy, longitude = long * accuracy)
      result.append(wgsCoordinate)
    }
    result.toIndexedSeq
  }

  @inline
  private def coordinateIncrement(result: Int): Int = {
    if ((result & 1) != 0) ~(result >> 1)
    else result >> 1
  }

  private def calculateIndexAndCoordinateIncrements(initialIndex: Int, polyline: String): (Int, Int) = {
    var currentResult: Int = 1
    var shift: Int = 0
    var currentCharacter: Int = 0
    var indexIncrement = 0
    do {
      indexIncrement += 1
      currentCharacter = polyline.charAt(initialIndex + indexIncrement - 1) - questionMarkPlusOne
      currentResult += currentCharacter << shift
      shift += accuracyDigits
    } while (currentCharacter >= 31)
    (indexIncrement, coordinateIncrement(currentResult))
  }

}
