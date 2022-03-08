package beam.utils.mapsapi.hereapi

import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration

import beam.agentsim.infrastructure.geozone.WgsCoordinate
import beam.utils.FileUtils
import beam.utils.mapsapi.Segment

class HereService(adapter: HereAdapter) {

  def findSegments(origin: WgsCoordinate, destination: WgsCoordinate): Future[Seq[Segment]] = {
    val pathFuture = adapter.findPath(origin, destination)
    val groupedSpans: Future[Iterator[(HerePath, TmpSpan)]] = pathFuture
      .map { path: HerePath =>
        path.spans.sliding(2).map { listOfSizeTwo =>
          val startOffset: Int = listOfSizeTwo.head.offset
          val endOffset = if (listOfSizeTwo.size == 1) startOffset else listOfSizeTwo(1).offset
          (
            path,
            TmpSpan(
              startOffset,
              endOffset,
              listOfSizeTwo.head.lengthInMeters,
              listOfSizeTwo.head.speedLimitInKph
            )
          )
        }
      }
    groupedSpans
      .map { values: Iterator[(HerePath, TmpSpan)] =>
        values.toList.map { case (path, span) =>
          val coordinates = path.coordinates.slice(span.startIndex, span.endIndex + 1)
          Segment(
            coordinates = coordinates,
            lengthInMeters = span.lengthInMeters,
            speedLimitInMetersPerSecond = span.speedLimitInMetersPerSecond
          )
        }
      }
  }

  case class TmpSpan(startIndex: Int, endIndex: Int, lengthInMeters: Int, speedLimitInMetersPerSecond: Option[Int])
}

object HereService {
  private val hereTimeout: Duration = Duration("15 seconds")

  def findSegments(
    apiKey: String,
    originCoordinate: WgsCoordinate,
    destinationCoordinate: WgsCoordinate
  ): Seq[Segment] = {
    FileUtils.using(new HereAdapter(apiKey)) { adapter =>
      val service = new HereService(adapter)
      val segFuture = service.findSegments(origin = originCoordinate, destination = destinationCoordinate)
      Await.result(segFuture, hereTimeout)
    }
  }

}
