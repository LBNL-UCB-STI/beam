package beam.agentsim.infrastructure.h3
import scala.collection.mutable.ArrayBuffer

case class BreakdownBucketGoal(index: H3Index, splitGoal: Int)

case class BreakdownPlan private (bucketsAndSizes: Map[H3Index, Int]) {

  lazy val plans: Seq[BreakdownBucketGoal] = {
    bucketsAndSizes.map {
      case (index, i) => BreakdownBucketGoal(index, i)
    }.toSeq
  }

}

object BreakdownPlan {

  def apply(content: H3Content, bucketsGoal: Int): BreakdownPlan = {

    val expectedBreakdown: Map[H3Index, Int] = {
      val totalElements: Int = content.numberOfPoints
      val maybeResult: Map[H3Index, Int] = content.buckets.mapValues { elements =>
        val percentage = elements.size.toDouble / totalElements
        Math.min(elements.size, Math.ceil(bucketsGoal * percentage)).toInt
      }
      val suggestedSplits = maybeResult.values.sum
      if (suggestedSplits > bucketsGoal) {
        val reduced = adjustExceedSplitSuggestions(bucketsGoal, maybeResult, suggestedSplits)
        maybeResult ++ reduced
      } else {
        maybeResult
      }
    }
    BreakdownPlan(expectedBreakdown)
  }

  private def adjustExceedSplitSuggestions(
    bucketsGoal: Int,
    maybeResult: Map[H3Index, Int],
    suggestedSplits: Int
  ): IndexedSeq[(H3Index, Int)] = {
    val toReduce = Math.abs(bucketsGoal - suggestedSplits)
    val reduceFrom = maybeResult
      .filterNot(_._2 == 1)
      .toIndexedSeq
      .sortBy {
        case (_, i) => i
      }
    subtractUsingRoundRobin(toReduce, reduceFrom)
  }

  def subtractUsingRoundRobin(
    reduceGoal: Int,
    originalContent: IndexedSeq[(H3Index, Int)]
  ): IndexedSeq[(H3Index, Int)] = {
    if (originalContent.isEmpty) {
      originalContent
    } else {
      val result: ArrayBuffer[(H3Index, Int)] = ArrayBuffer(originalContent: _*)
      var position = 0
      var valuesToReduce = reduceGoal
      while (valuesToReduce > 0) {
        val element = result(position)
        if (element._2 > 1) {
          result(position) = element.copy(_2 = element._2 - 1)
        }
        valuesToReduce -= 1
        position = (position + 1) % originalContent.size
      }
      result.toIndexedSeq
    }
  }

}
