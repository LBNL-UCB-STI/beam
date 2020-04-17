package beam.agentsim.infrastructure.geozone

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

  def numberOfPoints(elements: Seq[Hexagon[_]]): Int = {
    elements.map(_.totalNumberOfCoordinates).sum
  }

  override def generate(): GeoZoneSummary = {
    val elements = generateEqualDemandH3Indexes()
    val onlyLeaf = elements.map { hexagon =>
      GeoZoneSummaryItem(hexagon.index, hexagon.totalNumberOfCoordinates)
    }
    GeoZoneSummary(onlyLeaf)
  }

}
