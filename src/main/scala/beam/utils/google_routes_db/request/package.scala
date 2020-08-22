package beam.utils.google_routes_db

import java.io.StringReader

import org.supercsv.io.CsvMapReader
import org.supercsv.prefs.CsvPreference

import scala.collection.mutable

package object request {

  case class GoogleRouteRequest(
    vehicleId: String,
    vehicleType: String,
    departureTime: Int,
    originLat: Double,
    originLng: Double,
    destLat: Double,
    destLng: Double,
    simTravelTime: Int,
    googleTravelTime: Int,
    googleTravelTimeWithTraffic: Option[Int],
    euclideanDistanceInMeters: Double,
    legLength: Double,
    googleDistance: String
  )

  object csv {

    def parseGoogleTravelTimeEstimationCsv(text: String): Seq[GoogleRouteRequest] = {
      val csvReader = new CsvMapReader(new StringReader(text), CsvPreference.STANDARD_PREFERENCE)
      val header = csvReader.getHeader(true)
      val result = new mutable.ArrayBuffer[GoogleRouteRequest]()

      Iterator
        .continually(csvReader.read(header: _*))
        .takeWhile(_ != null)
        .foreach { entry ⇒ result.append(toGoogleRouteRequest(entry)) }

      result
    }

    def toGoogleRouteRequest(map: java.util.Map[String, String]): GoogleRouteRequest =
      GoogleRouteRequest(
        vehicleId = map.get("vehicleId"),
        vehicleType = map.get("vehicleType"),
        departureTime = map.get("departureTime").toInt,
        originLat = map.get("originLat").toDouble,
        originLng = map.get("originLng").toDouble,
        destLat = map.get("destLat").toDouble,
        destLng = map.get("destLng").toDouble,
        simTravelTime = map.get("simTravelTime").toInt,
        googleTravelTime = map.get("googleTravelTime").toInt,
        googleTravelTimeWithTraffic = Option(map.get("googleTravelTimeWithTraffic")).map(_.toInt),
        euclideanDistanceInMeters = map.get("euclideanDistanceInMeters").toDouble,
        legLength = map.get("legLength").toDouble,
        googleDistance = map.get("googleDistance")
      )
  }
}
