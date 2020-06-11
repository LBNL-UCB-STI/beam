package beam.utils.uber.movementdata

import beam.agentsim.infrastructure.geozone.WgsCoordinate
import beam.utils.GeoJsonReader
import beam.utils.csv.CsvWriter
import beam.utils.uber.movementdata.ConvertSpeedData.getLink
import beam.utils.uber.movementdata.TravelTimeReader.TravelTime
import com.vividsolutions.jts.geom.{Envelope, Geometry}
import org.opengis.feature.Feature
import org.opengis.feature.simple.SimpleFeature

import scala.util.Random

class ConvertTravelTimeData(
  val pathToGeoBoundaries: String,
  val pathToTravelTimesCsv: String,
  val boundingBox: Envelope,
  val samplePerHour: Int,
  val seed: Int
) {
  import ConvertTravelTimeData._

  private val movIdToCenter: Map[Int, WgsCoordinate] = GeoJsonReader.read(pathToGeoBoundaries, mapper).toMap

  def convert(): Unit = {
    val (travelTimes, toClose) = new TravelTimeReader(pathToTravelTimesCsv).read(_ => true)
    try {
      val onlyWithinBoundingBox = travelTimes.collect { case travelTime if withinBoundingBox(travelTime) => travelTime }.toArray
      writeToCsv(onlyWithinBoundingBox, "SanFrancisco-Full_travel-time.csv")
      println(s"onlyWithinBoundingBox: ${onlyWithinBoundingBox.length}")

      val rnd = new Random(seed)
      val sampled = onlyWithinBoundingBox
        .groupBy(x => x.hod)
        .flatMap {
          case (_, xs) =>
            rnd.shuffle(xs.toList).take(samplePerHour)
        }
        .toArray
        .sortBy(x => x.hod)
      println(s"sampled: ${sampled.length}")
      writeToCsv(sampled, s"SanFrancisco-Sampled_travel-time_${samplePerHour}.csv")
    } finally {
      toClose.close()
    }
  }

  private def writeToCsv(travelTimes: Iterable[TravelTime], filePath: String): Unit = {
    val headers = Array(
      "sourceid",
      "dstid",
      "hod",
      "mean_travel_time",
      "standard_deviation_travel_time",
      "start_lat",
      "start_lon",
      "end_lat",
      "end_lon",
      "link"
    )
    val cswWriter = new CsvWriter(filePath, headers)
    travelTimes.foreach { travelTime =>
      val srcCoord = movIdToCenter.getOrElse(
        travelTime.sourceId,
        throw new NoSuchElementException(s"sourceId ${travelTime.sourceId}")
      )
      val dstCoord =
        movIdToCenter.getOrElse(travelTime.dstId, throw new NoSuchElementException(s"dstId ${travelTime.dstId}"))
      val googleLink = getLink(srcCoord, dstCoord, travelTime.hod)
      cswWriter.write(
        travelTime.sourceId,
        travelTime.dstId,
        travelTime.hod,
        travelTime.meanTravelTime,
        travelTime.stdTravelTime,
        srcCoord.latitude,
        srcCoord.longitude,
        dstCoord.latitude,
        dstCoord.longitude,
        googleLink
      )
    }
    cswWriter.close()
  }

  private def withinBoundingBox(travelTime: TravelTime): Boolean = {
    val srcCoord =
      movIdToCenter.getOrElse(travelTime.sourceId, throw new NoSuchElementException(s"sourceId ${travelTime.sourceId}"))
    val dstCoord =
      movIdToCenter.getOrElse(travelTime.dstId, throw new NoSuchElementException(s"dstId ${travelTime.dstId}"))
    boundingBox.contains(srcCoord.longitude, srcCoord.latitude) && boundingBox.contains(
      dstCoord.longitude,
      dstCoord.latitude
    )
  }

}

object ConvertTravelTimeData {

  def mapper(feature: Feature): (Int, WgsCoordinate) = {
    val centroid = feature.asInstanceOf[SimpleFeature].getDefaultGeometry.asInstanceOf[Geometry].getCentroid
    val wgsCoord = WgsCoordinate(centroid.getY, centroid.getX)
    val movId = feature.getProperty("MOVEMENT_ID").getValue.toString.toInt
    (movId, wgsCoord)
  }

  def main(args: Array[String]): Unit = {
    val pathToGeoBoundaries: String = "D:/Work/beam/Uber/SpeedData/san_francisco_taz.json"
    val pathToTravelTimesCsv: String =
      "D:/Work/beam/Uber/SpeedData/san_francisco-taz-2019-4-OnlyWeekdays-HourlyAggregate.csv"

    // https://www.google.com/maps/d/drive?state=%7B%22ids%22%3A%5B%221n-UDzL-FBZzyXBs7tSfYgb37GpJ93_Az%22%5D%2C%22action%22%3A%22open%22%2C%22userId%22%3A%22111892074776602978600%22%7D&usp=sharing
    // Latitude values increase or decrease along the vertical axis, the Y axis, coordinate is between -90 and 90.
    // Longitude changes value along the horizontal access, the X axis, coordinate is between -180 and 180.
    // X = Longitude, Y = Latitude
    val boundingBox: Envelope = new Envelope(-122.546046192, -122.330412968, 37.655512625, 37.811362211)
    val c = new ConvertTravelTimeData(pathToGeoBoundaries, pathToTravelTimesCsv, boundingBox, 200, 42)
    c.convert()
  }
}
