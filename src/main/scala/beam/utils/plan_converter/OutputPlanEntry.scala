package beam.utils.plan_converter

case class OutputPlanEntry(
  personId: Int,
  planElement: ActivityType,
  planElementIndex: Int,
  activityType: String,
  x: Double,
  y: Double,
  mode: String
) {
  def toCsv(): Seq[String] = {
  Seq.empty
  }
}
