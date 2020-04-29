package beam.agentsim.infrastructure.geozone

object TopDownEqualDemandsSplitter {

  val MaxResolution = 12

  private[geozone] def chooseOneToSplit(
    children: IndexedSeq[Hexagon[_]],
    bucketsGoal: Int,
    numberOfPoints: Int
  ): Int = {
    val diffAndPosition: Map[Double, Int] = children
      .filter(_.index.resolution < MaxResolution)
      .zipWithIndex
      .map {
        case (child, position) =>
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
