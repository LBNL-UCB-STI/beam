package beam.utils.uber.movementdata

import java.io.Closeable
import java.time.ZonedDateTime

import beam.utils.ProfilingUtils
import beam.utils.csv.GenericCsvReader

class SpeedReader(val pathToSpeedCsv: String) {
  import SpeedReader._

  def read(filter: SpeedData => Boolean): (Iterator[SpeedData], Closeable) = {
    GenericCsvReader.readAs[SpeedData](pathToSpeedCsv, toSpeedData, filter)
  }
}

object SpeedReader {
  val mphToMs: Double = 0.44704

  def toSpeedData(rec: java.util.Map[String, String]): SpeedData = {
    SpeedData(
      year = rec.get("year").toShort,
      month = rec.get("month").toByte,
      day = rec.get("day").toByte,
      hour = rec.get("hour").toByte,
      ts = ZonedDateTime.parse(rec.get("utc_timestamp")),
      wayId = rec.get("osm_way_id").toLong,
      startNodeId = rec.get("osm_start_node_id").toLong,
      endNodeId = rec.get("osm_end_node_id").toLong,
      meanSpeedInMetersPerSecond = rec.get("speed_mph_mean").toDouble * mphToMs,
      stdDevSpeed = rec.get("speed_mph_stddev").toDouble * mphToMs,
    )
  }

  case class SpeedData(
    year: Short,
    month: Byte,
    day: Byte,
    hour: Byte,
    ts: ZonedDateTime,
    wayId: Long,
    startNodeId: Long,
    endNodeId: Long,
    meanSpeedInMetersPerSecond: Double,
    stdDevSpeed: Double
  )

  def main(args: Array[String]): Unit = {
    val path: String = "D:/Work/beam/Uber/SpeedData/1.csv"
    val sr = new SpeedReader(path)
    val (speeds, toClose) = sr.read(x => true)
    try {
      val matSpeeds = ProfilingUtils.timed("Read all the speeds", x => println(x)) {
        speeds.toArray
      }
      println(s"matSpeeds: ${matSpeeds.length}")
    } finally {
      toClose.close()
    }
  }
}
