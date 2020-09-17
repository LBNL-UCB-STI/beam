package beam.agentsim.events.handling

import java.io.StringReader

import beam.agentsim.events.PathTraversalEvent
import beam.sim.common.GeoUtils
import beam.utils.csv.CsvWriter
import beam.utils.FileUtils.using
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.vehicles.Vehicle
import org.supercsv.io.CsvMapReader
import org.supercsv.prefs.CsvPreference

import scala.collection.mutable

case class GoogleTravelTimeEstimationEntry(
  requestId: String,
  vehicleId: Id[Vehicle],
  vehicleType: String,
  departureTime: Int,
  originLat: Double,
  originLng: Double,
  destLat: Double,
  destLng: Double,
  simTravelTime: Int,
  googleTravelTime: Long,
  googleTravelTimeWithTraffic: Option[Long],
  euclideanDistanceInMeters: Double,
  legLength: Double,
  googleDistance: Long
)

object GoogleTravelTimeEstimationEntry {

  import scala.language.implicitConversions
  private val decimalFormat = new java.text.DecimalFormat("#.########")
  implicit def doubleToString(x: Double): String = decimalFormat.format(x)
  implicit def intToString(x: Int): String = x.toString
  implicit def optionIntToString(x: Option[Int]): String = x.map(intToString).getOrElse("")
  implicit def longToString(x: Long): String = x.toString
  implicit def optioLlongToString(x: Option[Long]): String = x.map(longToString).getOrElse("")

  def toRow(entry: GoogleTravelTimeEstimationEntry): Vector[String] = {
    Vector[String](
      entry.requestId,
      entry.vehicleId.toString,
      entry.vehicleType,
      entry.departureTime,
      entry.originLat,
      entry.originLng,
      entry.destLat,
      entry.destLng,
      entry.simTravelTime,
      entry.googleTravelTime,
      entry.googleTravelTimeWithTraffic,
      entry.euclideanDistanceInMeters,
      entry.legLength,
      entry.googleDistance
    )
  }

  def fromJulMap(map: java.util.Map[String, String]): GoogleTravelTimeEstimationEntry = {
    GoogleTravelTimeEstimationEntry(
      requestId = map.get("requestId"),
      vehicleId = Id.createVehicleId(map.get("vehicleId")),
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
      googleDistance = map.get("googleDistance").toInt
    )
  }

  def fromPTE(
    pte: PathTraversalEvent,
    requestId: String,
    googleTravelTime: Long,
    googleTravelTimeWithTraffic: Option[Long],
    googleDistance: Long,
    geoUtils: GeoUtils
  ): GoogleTravelTimeEstimationEntry = {
    GoogleTravelTimeEstimationEntry(
      requestId = requestId,
      vehicleId = pte.vehicleId,
      vehicleType = pte.vehicleType,
      departureTime = pte.departureTime,
      originLat = pte.startY,
      originLng = pte.startX,
      destLat = pte.endY,
      destLng = pte.endX,
      simTravelTime = pte.arrivalTime - pte.departureTime,
      googleTravelTime = googleTravelTime,
      googleTravelTimeWithTraffic = googleTravelTimeWithTraffic,
      euclideanDistanceInMeters = geoUtils.distLatLon2Meters(
        new Coord(pte.startX, pte.startY),
        new Coord(pte.endX, pte.endY)
      ),
      legLength = pte.legLength,
      googleDistance = googleDistance
    )
  }

  object Csv {

    val headers = Vector(
      "requestId",
      "vehicleId",
      "vehicleType",
      "departureTime",
      "originLat",
      "originLng",
      "destLat",
      "destLng",
      "simTravelTime",
      "googleTravelTime",
      "googleTravelTimeWithTraffic",
      "euclideanDistanceInMeters",
      "legLength",
      "googleDistance"
    )

    def writeEntries(entries: Seq[GoogleTravelTimeEstimationEntry], filePath: String): Unit = {
      using(new CsvWriter(filePath, headers)) { csvWriter =>
        entries.map(GoogleTravelTimeEstimationEntry.toRow).foreach(csvWriter.writeRow)
      }
    }

    def parseEntries(text: String): Seq[GoogleTravelTimeEstimationEntry] = {
      val csvReader = new CsvMapReader(new StringReader(text), CsvPreference.STANDARD_PREFERENCE)
      val header = csvReader.getHeader(true)
      val result = new mutable.ArrayBuffer[GoogleTravelTimeEstimationEntry]()

      Iterator
        .continually(csvReader.read(header: _*))
        .takeWhile(_ != null)
        .foreach { rowMap =>
          result.append(GoogleTravelTimeEstimationEntry.fromJulMap(rowMap))
        }

      result
    }
  }
}
