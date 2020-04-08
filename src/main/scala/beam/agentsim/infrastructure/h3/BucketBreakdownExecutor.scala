package beam.agentsim.infrastructure.h3

case class BucketBreakdownExecutor(originalContent: H3Content, expectedBuckets: Int) {

  if (expectedBuckets > originalContent.numberOfPoints) {
    throw H3InvalidOutputSizeException(expectedBuckets, originalContent.numberOfBuckets)
    new IllegalArgumentException("The outputSize should be smaller or equal to the number of coordinates")
  }

  val breakdownPlan: BreakdownPlan = BreakdownPlan(originalContent, expectedBuckets)

  def execute(): H3Content = {
    val content = originalContent
    val result: Seq[H3Content] = breakdownPlan.plans.map { goal: BreakdownBucketGoal =>
      if (goal.splitGoal == 1) {
        val resultTmp = content.contentFromBucket(goal.index).increaseBucketResolutionWithoutSplit(goal.index)
        resultTmp
      } else {
        val maybeResult = content.increaseBucketResolutionWithSplit(goal.index)
        if (contentNotSplitOrAchievedGoalSplit(maybeResult, goal)) {
          maybeResult
        } else {
          val newBreakDown = BucketBreakdownExecutor(maybeResult, goal.splitGoal)
          val newResult = newBreakDown.execute()
          newResult
        }
      }
    }
    result.reduce((a, b) => a.merge(b))
  }

  @inline
  def contentNotSplitOrAchievedGoalSplit(content: H3Content, goal: BreakdownBucketGoal): Boolean = {
    content.numberOfBuckets <= 1 || content.numberOfBuckets >= goal.splitGoal
  }

}
