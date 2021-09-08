package beam.agentsim.infrastructure.geozone.aggregation

import scala.collection.parallel.{ParSeq, ParSet}

import beam.agentsim.infrastructure.geozone._
import beam.agentsim.infrastructure.geozone.GeoZone.GeoZoneContent
import beam.utils.map.SequenceUtil

class WgsCoordinateH3IndexMapper(
  wgsCoordinates: ParSet[WgsCoordinate],
  targetIndexes: ParSet[H3Index]
) extends H3IndexMapper {

  private val resolutionsDescendingOrdered: Seq[Int] =
    targetIndexes.map(_.resolution).seq.toSeq.sorted(Ordering.Int.reverse)

  override lazy val generateContent: GeoZoneContent = {
    val result: ParSeq[(H3Index, WgsCoordinate)] = wgsCoordinates.toSeq.flatMap { coordinate =>
      findIndex(coordinate).map { index =>
        index -> coordinate
      }
    }
    SequenceUtil.groupBy(result)
  }

  override lazy val generateSummary: GeoZoneSummary = {
    val items = generateContent.map { case (index, coordinates) =>
      GeoZoneSummaryItem(index, coordinates.size)
    }.toSeq
    GeoZoneSummary(items)
  }

  def findIndex(coordinate: WgsCoordinate): Option[H3Index] = {
    resolutionsDescendingOrdered.toIterator
      .map(resolution => H3Wrapper.getIndex(coordinate, resolution))
      .find(targetIndexes.contains)
  }

}
