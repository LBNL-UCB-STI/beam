package beam.utils.transit

import java.time.LocalTime
import java.time.format.DateTimeFormatter

import beam.utils.{CsvFileUtils, FileUtils}
import com.conveyal.r5.transit.TransitLayer

import scala.collection.JavaConverters._

/** Generates/Loads CSV in the following format:
  * {{{
  *   trip_id,start_time,end_time,headway_secs,exact_times
  *   bus:B1-EAST-1,06:00:00,22:00:00,150,
  *   train:R2-NORTH-1,07:00:00,23:00:00,300,
  *   ...
  * }}}
  * where "bus:", "train:" etc. prefixes for tripId are the names of GTFS feeds,
  * everything else have the same format as <a href="https://developers.google.com/transit/gtfs/reference#frequenciestxt">frequencies.txt</a>
  * <br/>
  * Therefore part "bus:B1" will be considered as "routeId", whole thing "bus:B1-EAST-1" - as a tripId
  * in the [[com.conveyal.r5.transit.TransportNetwork#transitLayer]]
  *
  * @return set of FrequencyAdjustment objects
  */
object FrequencyAdjustmentUtils {

  case class FrequencyAdjustment(
    routeId: String,
    tripId: String,
    startTime: LocalTime,
    endTime: LocalTime,
    headwaySecs: Int,
    exactTimes: Option[Int] = None
  )

  def generateFrequencyAdjustmentCsvFile(transitLayer: TransitLayer, freqAdjustmentFilePath: String): Unit = {
    val rows = if (transitLayer.hasFrequencies) {
      transitLayer.tripPatterns.asScala.flatMap { tp =>
        tp.tripSchedules.asScala.flatMap { ts =>
          ts.startTimes.zip(ts.endTimes).zip(ts.headwaySeconds).map {
            case ((startTime, endTime), headwaySeconds) =>
              s"${ts.tripId},${secondsToTime(startTime)},${secondsToTime(endTime)},$headwaySeconds,"
          }
        }
      }.toList
    } else {
      Nil
    }

    FileUtils.writeToFile(
      freqAdjustmentFilePath,
      Some("trip_id,start_time,end_time,headway_secs,exact_times"),
      rows.mkString("\n"),
      Option.empty
    )
  }

  def loadFrequencyAdjustmentCsvFile(freqAdjustmentFilePath: String): Set[FrequencyAdjustment] =
    CsvFileUtils
      .readCsvFileByLineToList(freqAdjustmentFilePath) { row =>
        FrequencyAdjustment(
          row.get("trip_id").split("-").head,
          row.get("trip_id"),
          LocalTime.parse(row.get("start_time")),
          LocalTime.parse(row.get("end_time")),
          row.get("headway_secs").toInt,
          Option(row.get("exact_times")).map(_.toInt)
        )
      }
      .toSet

  private def secondsToTime(secondsOfDay: Int): String =
    LocalTime
      .ofSecondOfDay(secondsOfDay)
      .format(DateTimeFormatter.ISO_LOCAL_TIME)
}
