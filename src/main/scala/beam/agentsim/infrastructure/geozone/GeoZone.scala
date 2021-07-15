package beam.agentsim.infrastructure.geozone

import scala.collection.parallel.ParSet

import beam.agentsim.infrastructure.geozone.H3Wrapper.geoToH3Address

class GeoZone(val coordinates: ParSet[WgsCoordinate]) {

  def this(coordinates: Set[WgsCoordinate]) {
    this(coordinates.par)
  }

  def includeBoundBoxPoints: GeoZone = {
    val newPoints = WgsRectangle.from(coordinates).coordinates
    val allPoints = coordinates ++ newPoints
    new GeoZone(allPoints)
  }

}

object GeoZone {
  private[geozone] type GeoZoneContent = Map[H3Index, Set[WgsCoordinate]]

  private[geozone] def generateContent(elements: ParSet[WgsCoordinate], resolution: Int): GeoZoneContent = {
    if (elements.isEmpty) {
      Map.empty
    } else {
      elements
        .map { point =>
          val indexResult = geoToH3Address(point, resolution)
          H3Index(indexResult) -> point
        }
        .groupBy(_._1)
        .mapValues { x =>
          val result: Set[WgsCoordinate] = x.map(_._2).toSet.seq
          result
        }
        .seq
        .toMap
    }
  }

  private[geozone] def mapCoordinateToIndex(content: GeoZoneContent): Map[WgsCoordinate, H3Index] = {
    content.flatMap { case (index, coordinates) =>
      coordinates.map(coordinate => (coordinate, index))
    }
  }
}
