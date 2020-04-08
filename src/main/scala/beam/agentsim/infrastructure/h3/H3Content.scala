package beam.agentsim.infrastructure.h3

import beam.agentsim.infrastructure.h3.H3Content.MaxResolution
import beam.agentsim.infrastructure.h3.H3Wrapper.geoToH3Address

case class H3Content(buckets: Map[H3Index, Set[H3Point]]) {

  def merge(otherContent: H3Content): H3Content = {
    val merged = buckets.toSeq ++ otherContent.buckets.toSeq
    val groupedByIndex = merged.groupBy(_._1)
    val cleaned: Map[H3Index, Set[H3Point]] = groupedByIndex.mapValues(x => x.flatMap(_._2).toSet)
    H3Content(cleaned)
  }

  def contentFromBucket(index: H3Index): H3Content = {
    H3Content(Map(index -> buckets(index)))
  }

  def increaseResolution: H3Content = {
    val tmp: Seq[(H3Index, H3Point)] = buckets
      .map {
        case (currentIndex, currentPoints) =>
          val currentResolution = currentIndex.resolution
          val nextResolution = Math.min(currentResolution + 1, MaxResolution)
          currentPoints.map { point =>
            val indexResult = geoToH3Address(point, nextResolution)
            H3Index(indexResult) -> point
          }
      }
      .toSeq
      .flatten
    val result: Map[H3Index, Set[H3Point]] = tmp.groupBy(_._1).mapValues(_.map(_._2).toSet)
    H3Content(result)
  }

  def increaseBucketResolutionWithSplit(bucketIndex: H3Index): H3Content = {
    val initial = H3Content(asBucket(bucketIndex))
    var current = initial
    do {
      current = current.increaseResolution
    } while (current.maxResolution < MaxResolution && current.numberOfBuckets == initial.numberOfBuckets)
    current
  }

  def increaseBucketResolutionWithoutSplit(bucketIndex: H3Index): H3Content = {
    var previousResult: H3Content = H3Content(asBucket(bucketIndex))
    var currentResult: H3Content = previousResult
    while (currentResult.numberOfBuckets == 1 && currentResult.maxResolution < MaxResolution) {
      previousResult = currentResult
      currentResult = currentResult.increaseResolution
    }
    if (currentResult.numberOfBuckets == 1) {
      currentResult
    } else {
      previousResult
    }
  }

  @inline
  def asBucket(bucketIndex: H3Index): H3Bucket = H3Bucket(bucketIndex, buckets(bucketIndex))

  def asBuckets: Iterable[H3Bucket] = indexes.view.map(asBucket)

  lazy val indexes: Set[H3Index] = buckets.view.map(_._1).toSet

  lazy val maxResolution: Int = {
    if (buckets.isEmpty) {
      H3Content.MinResolution
    } else {
      buckets.view.map(_._1.resolution).max
    }
  }

  lazy val numberOfBuckets: Int = buckets.size

  lazy val numberOfPoints: Int = buckets.view.map(_._2.size).sum

}

object H3Content {

  val MinResolution: Int = 0
  val MaxResolution: Int = 15
  val Empty: H3Content = H3Content(Map.empty[H3Index, Set[H3Point]])

  def apply(bucket: H3Bucket): H3Content = new H3Content(Map(bucket.index -> bucket.points))

  def fromPoints(elements: Set[H3Point], resolution: Int): H3Content = {
    if (elements.isEmpty) {
      Empty
    } else {
      val tmp: Map[H3Index, Set[H3Point]] = elements.par
        .map { point =>
          val indexResult = geoToH3Address(point, resolution)
          H3Index(indexResult) -> point
        }
        .groupBy(_._1)
        .mapValues(_.map(_._2).seq)
        .seq.toMap
      H3Content(tmp)
    }
  }

}
