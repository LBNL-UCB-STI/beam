package beam.agentsim.infrastructure.geozone

object TopDownEqualDemandSplitter {

  private[geozone] def chooseOneToSplit(
    children: IndexedSeq[Hexagon[_]],
    bucketsGoal: Int,
    numberOfPoints: Int,
    maxResolution: Int = 12
  ): Int = {
    val diffAndPosition: Map[Double, Int] = children
      .filter(_.index.resolution < maxResolution)
      .zipWithIndex
      .map { case (child, position) =>
        val percentage = child.totalNumberOfCoordinates.toDouble / numberOfPoints
        val expectedBuckets = bucketsGoal * percentage
        val current = child.totalNumberOfBuckets
        val diff = if (expectedBuckets == 0) Double.MinValue else Math.abs(current - expectedBuckets)
        diff -> position
      }
      .toMap
    if (diffAndPosition.isEmpty) {
      0
    } else {
      diffAndPosition.maxBy(_._1)._2
    }
  }

}
