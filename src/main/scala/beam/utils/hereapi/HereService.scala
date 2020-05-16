package beam.utils.hereapi

import java.io.Closeable
import java.nio.file.Path
import java.util.Objects

import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration

import beam.agentsim.infrastructure.geozone.GeoZoneUtil.toWgsCoordinate
import beam.agentsim.infrastructure.geozone.WgsCoordinate
import beam.utils.FileUtils
import beam.utils.csv.GenericCsvReader

class HereService(adapter: HereAdapter) {

  def findSegments(origin: WgsCoordinate, destination: WgsCoordinate): Future[Seq[HereSegment]] = {
    val pathFuture = adapter.findPath(origin, destination)
    val groupedSpans: Future[Iterator[(HerePath, TmpSpan)]] = pathFuture
      .map { path =>
        path.spans.sliding(2).map { listOfSizeTwo =>
          val startCoordinate = listOfSizeTwo.head.offset
          val endCoordinate = listOfSizeTwo(1).offset
          (
            path,
            TmpSpan(
              startCoordinate,
              endCoordinate,
              listOfSizeTwo.head.lengthInMeters,
              listOfSizeTwo.head.speedLimitInKph
            )
          )
        }
      }
    groupedSpans
      .map { values: Iterator[(HerePath, TmpSpan)] =>
        values.toList.map {
          case (path, span) =>
            val coordinates = path.coordinates.slice(span.startIndex, span.endIndex + 1)
            HereSegment(coordinates, span.lengthInMeters, span.speedLimitInKph)
        }
      }
  }

  case class TmpSpan(startIndex: Int, endIndex: Int, lengthInMeters: Int, speedLimitInKph: Option[Int])
}

object HereService {

  def findSegments(
    apiKey: String,
    originCoordinate: WgsCoordinate,
    destinationCoordinate: WgsCoordinate
  ): Seq[HereSegment] = {
    FileUtils.using(new HereAdapter(apiKey)) { adapter =>
      val service = new HereService(adapter)
      val segFuture = service.findSegments(origin = originCoordinate, destination = destinationCoordinate)
      Await.result(segFuture, Duration("5 seconds"))
    }
  }

  def fromCsv(file: Path): Seq[HereSegment] = {
    val (iter: Iterator[HereSegment], toClose: Closeable) =
      GenericCsvReader.readAs[HereSegment](file.toString, toHereSegment, _ => true)
    try {
      iter.toList
    } finally {
      toClose.close()
    }
  }

  private def deserializeCoordinates(str: String): Seq[WgsCoordinate] = {
    println(str)
    val arr: Array[String] = str.split('|')
    arr.map { eachElement =>
      val arr = eachElement.split("/")
      WgsCoordinate(arr(0).toDouble, arr(1).toDouble)
    }
  }

  def toHereSegment(rec: java.util.Map[String, String]): HereSegment = {
    val vWgsCoordinates = rec.get("wgsCoordinates")
    val vLengthInMeters = rec.get("lengthInMeters").toInt
    val vSpeedLimitInKph = rec.getOrDefault("speedLimitInKph", "")
    HereSegment(
      deserializeCoordinates(vWgsCoordinates),
      lengthInMeters = vLengthInMeters,
      speedLimitInKph = vSpeedLimitInKph match {
        case value if value == null || value.isEmpty => None
        case value                                   => Some(value.toInt)
      }
    )
  }

}
