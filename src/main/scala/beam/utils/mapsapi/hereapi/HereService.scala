package beam.utils.mapsapi.hereapi

import java.io.Closeable
import java.nio.file.Path

import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration

import beam.agentsim.infrastructure.geozone.WgsCoordinate
import beam.utils.FileUtils
import beam.utils.csv.GenericCsvReader
import beam.utils.mapsapi.Segment

class HereService(adapter: HereAdapter) {

  def findSegments(origin: WgsCoordinate, destination: WgsCoordinate): Future[Seq[Segment]] = {
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
            Segment(
              coordinates = coordinates,
              lengthInMeters = span.lengthInMeters,
              speedLimitInKph = span.speedLimitInKph
            )
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
  ): Seq[Segment] = {
    FileUtils.using(new HereAdapter(apiKey)) { adapter =>
      val service = new HereService(adapter)
      val segFuture = service.findSegments(origin = originCoordinate, destination = destinationCoordinate)
      Await.result(segFuture, Duration("5 seconds"))
    }
  }

}
