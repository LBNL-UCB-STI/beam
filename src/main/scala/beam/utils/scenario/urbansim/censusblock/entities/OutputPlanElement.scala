package beam.utils.scenario.urbansim.censusblock.entities

case class OutputPlanElement(
  personId: Int,
  planElement: ActivityType,
  planElementIndex: Int,
  activityType: Option[String],
  x: Option[Double],
  y: Option[Double],
  endTime: Option[Double],
  mode: Option[String]
) {

  def toRow(): Map[String, Any] = Map(
    "personId"         -> personId,
    "planElement"      -> planElement.toString,
    "planElementIndex" -> planElementIndex,
    "activityType"     -> activityType.getOrElse(""),
    "x"                -> x.orNull,
    "y"                -> y.orNull,
    "endTime"          -> endTime.orNull,
    "mode"             -> mode.getOrElse("")
  )
}

object OutputPlanElement {

  val headers: Seq[String] =
    Seq("personId", "planElement", "planElementIndex", "activityType", "x", "y", "endTime", "mode")
}
