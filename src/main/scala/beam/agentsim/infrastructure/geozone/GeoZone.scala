package beam.agentsim.infrastructure.geozone

import scala.collection.parallel.ParSet

import H3Wrapper.geoToH3Address

class GeoZone(coordinates: ParSet[WgsCoordinate]) {

  def this(coordinates: Set[WgsCoordinate]) {
    this(coordinates.par)
  }

  def includeBoundBoxPoints: GeoZone = {
    val newPoints = WgsRectangle.from(coordinates).coordinates
    val allPoints = coordinates ++ newPoints
    new GeoZone(allPoints)
  }

  def topDownEqualDemandsGenerator(
    expectedNumberOfBuckets: Int,
    initialResolution: Int = 2
  ): TopDownEqualDemandsGeoZoneHexGenerator = {
    val allContent = GeoZone.generateContent(coordinates, initialResolution)
    val allHexagons: IndexedSeq[HexagonLeaf] = allContent.map {
      case (index, points) => HexagonLeaf(index, points)
    }.toIndexedSeq
    new TopDownEqualDemandsGeoZoneHexGenerator(allHexagons, expectedNumberOfBuckets)
  }

}

object GeoZone {
  private[geozone] type GeoZoneContent = Map[GeoIndex, Set[WgsCoordinate]]

  private[geozone] def generateContent(elements: ParSet[WgsCoordinate], resolution: Int): GeoZoneContent = {
    if (elements.isEmpty) {
      Map.empty
    } else {
      elements
        .map { point =>
          val indexResult = geoToH3Address(point, resolution)
          GeoIndex(indexResult) -> point
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

}
