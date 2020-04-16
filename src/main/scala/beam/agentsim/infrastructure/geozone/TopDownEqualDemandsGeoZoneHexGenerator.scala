package beam.agentsim.infrastructure.geozone

import scala.collection.parallel.ParSet

import beam.agentsim.infrastructure.geozone.GeoZone.GeoZoneContent

class TopDownEqualDemandsGeoZoneHexGenerator private[geozone] (
  initialContent: IndexedSeq[HexagonLeaf],
  bucketsGoal: Int,
  iterationsThreshold: Int = 1000
) extends GeoZoneHexGenerator {

  private def generateEqualDemandH3Indexes(): Seq[Hexagon[_]] = {
    var result: IndexedSeq[Hexagon[_]] = initialContent
    var totalSize = result.map(_.totalNumberOfBuckets).sum
    var counter = 0
    while (totalSize < bucketsGoal & counter < iterationsThreshold) {
      counter += 1
      val position = TopDownEqualDemandsSplitter.chooseOneToSplit(result, bucketsGoal, numberOfPoints(result))
      val afterSplit: Seq[HexagonBranch] = result(position).split(bucketsGoal)
      result = result.patch(position, afterSplit, 1)
      totalSize = result.map(_.totalNumberOfBuckets).sum
    }
    result
  }

  private def numberOfPoints(elements: Seq[Hexagon[_]]): Int = {
    elements.map(_.totalNumberOfCoordinates).sum
  }

  override def generateSummary(): GeoZoneSummary = {
    val elements = generateEqualDemandH3Indexes()
    val onlyLeaf = elements.map { hexagon =>
      GeoZoneSummaryItem(hexagon.index, hexagon.totalNumberOfCoordinates)
    }
    GeoZoneSummary(onlyLeaf)
  }

  def generateContent(): GeoZoneContent = {
    val elements = generateEqualDemandH3Indexes()
    elements
      .filter(_.isInstanceOf[HexagonLeaf])
      .map { hexagon =>
        val leaf = hexagon.asInstanceOf[HexagonLeaf]
        (hexagon.index, leaf.coordinates)
      }
      .toMap
  }

}

object TopDownEqualDemandsGeoZoneHexGenerator {

  def from(
    coordinates: ParSet[WgsCoordinate],
    expectedNumberOfBuckets: Int,
    initialResolution: Int
  ): TopDownEqualDemandsGeoZoneHexGenerator = {
    val allContent: GeoZoneContent = GeoZone.generateContent(coordinates, initialResolution)
    val allHexagons: IndexedSeq[HexagonLeaf] = allContent.map {
      case (index, points) => HexagonLeaf(index, points)
    }.toIndexedSeq
    new TopDownEqualDemandsGeoZoneHexGenerator(allHexagons, expectedNumberOfBuckets)
  }

}
