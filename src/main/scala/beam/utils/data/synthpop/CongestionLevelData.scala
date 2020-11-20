package beam.utils.data.synthpop

import beam.utils.csv.GenericCsvReader

import scala.util.Try

trait CongestionLevelData {
  def level(timeStampSeconds: Int): Double
}

class CsvCongestionLevelData(pathToCsv: String) extends CongestionLevelData {
  private val hourToCongestionLevel: Map[Int, Double] = {
    val (it, toClose) = GenericCsvReader.readAs[(Int, Double)](pathToCsv, toCongestionData, x => true)
    try {
      it.map {
        case (hour, level) =>
          hour -> level
      }.toMap
    } finally {
      Try(toClose.close())
    }
  }

  override def level(timeStampSeconds: Int): Double = {
    val hour = timeStampSeconds / 3600
    hourToCongestionLevel(hour)
  }

  private def toCongestionData(rec: java.util.Map[String, String]): (Int, Double) = {
    val hour = rec.get("hour").toInt
    val level = rec.get("congestion_level_percent").toDouble
    (hour, level)
  }
}
