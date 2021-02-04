package beam.utils.mapsapi

import java.io.Closeable
import java.nio.file.Path

import beam.agentsim.infrastructure.geozone.WgsCoordinate
import beam.utils.csv.GenericCsvReader

object SegmentSerializer {

  def fromCsv(file: Path): Seq[Segment] = {
    val (iter: Iterator[Segment], toClose: Closeable) =
      GenericCsvReader.readAs[Segment](file.toString, toHereSegment, _ => true)
    try {
      iter.toList
    } finally {
      toClose.close()
    }
  }

  private def deserializeCoordinates(str: String): Seq[WgsCoordinate] = {
    val arr: Array[String] = str.split('|')
    arr.map { eachElement =>
      val arr = eachElement.split("/")
      WgsCoordinate(arr(0).toDouble, arr(1).toDouble)
    }
  }

  def toHereSegment(rec: java.util.Map[String, String]): Segment = {
    val vWgsCoordinates = rec.get("wgsCoordinates")
    val vLengthInMeters = rec.get("lengthInMeters").toInt
    val vDurationInSeconds = rec.getOrDefault("durationInSeconds", "")
    val vSpeedLimitInMps = rec.getOrDefault("speedLimitInMps", "")
    Segment(
      deserializeCoordinates(vWgsCoordinates),
      lengthInMeters = vLengthInMeters,
      durationInSeconds = vDurationInSeconds match {
        case value if value == null || value.isEmpty => None
        case value                                   => Some(value.toInt)
      },
      speedLimitInMetersPerSecond = vSpeedLimitInMps match {
        case value if value == null || value.isEmpty => None
        case value                                   => Some(value.toInt)
      }
    )
  }

}
