package beam.utils.uber.movementdata

import java.io.Closeable

import beam.utils.csv.GenericCsvReader

class TravelTimeReader(val pathToSpeedCsv: String) {

  import TravelTimeReader._

  def read(filter: TravelTime => Boolean): (Iterator[TravelTime], Closeable) = {
    GenericCsvReader.readAs[TravelTime](pathToSpeedCsv, toTravelTime, filter)
  }
}

object TravelTimeReader {

  case class TravelTime(sourceId: Int, dstId: Int, hod: Byte, meanTravelTime: Double, stdTravelTime: Double)

  def toTravelTime(rec: java.util.Map[String, String]): TravelTime = {
    TravelTime(
      sourceId = rec.get("sourceid").toInt,
      dstId = rec.get("dstid").toInt,
      hod = rec.get("hod").toByte,
      meanTravelTime = rec.get("mean_travel_time").toDouble,
      stdTravelTime = rec.get("standard_deviation_travel_time").toDouble
    )
  }

}
