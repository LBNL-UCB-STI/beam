package beam.side.speed.parser
import scala.annotation.tailrec

object Median {

  @tailrec private def findKMedian(arr: Array[Float], k: Int): Float = {
    val a = chooseRandomPivot(arr)
    val (s, b) = arr partition (a >)
    if (s.length == k) a
    // The following test is used to avoid infinite repetition
    else if (s.isEmpty) {
      val (s, b) = arr partition (a ==)
      if (s.length > k) a
      else findKMedian(b, k - s.length)
    } else if (s.length < k) findKMedian(b, k - s.length)
    else findKMedian(s, k)
  }

  def findMedian(arr: Array[Float]): Float =
    findKMedian(arr, (arr.length - 1) / 2)

  private def chooseRandomPivot(arr: Array[Float]): Float =
    arr(scala.util.Random.nextInt(arr.length))
}
