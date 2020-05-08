package beam.utils.hereapi

import beam.agentsim.infrastructure.geozone.WgsCoordinate
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

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
