package beam.agentsim.infrastructure.h3

class H3ContentOverlapCleaner(originalContent: H3Content) {

  def execute(): H3Content = {
    val bucketsSortedByResolution: Seq[(H3Index, Set[H3Point])] =
      originalContent.buckets.toSeq.groupBy(_._1.resolution).toSeq.sortBy(_._1).flatMap(_._2)

    val newContentAsSeq = bucketsSortedByResolution.foldLeft(Seq.empty[(H3Index, Set[H3Point])]) {
      case (result, currentBucket @ (currentIndex: H3Index, currentPoints: Set[H3Point])) =>
        val increased = if (hasChildrenInTheNext2Levels(currentIndex)) {
          H3Content(
            Map(currentIndex -> currentPoints)
          ).increaseResolution.buckets.toSeq
        } else {
          Seq(currentBucket)
        }
      result ++ increased
    }
    H3Content(newContentAsSeq.toMap)
  }

  private def hasChildrenInTheNext2Levels(index: H3Index): Boolean = {
    val nextLevel1 = H3Wrapper.getChildren(index).intersect(originalContent.indexes)
    if (nextLevel1.isEmpty) {
      H3Wrapper
        .getChildren(index, Math.min(index.resolution + 2, H3Content.MaxResolution))
        .intersect(originalContent.indexes)
        .nonEmpty
    } else {
      true
    }
  }
}
