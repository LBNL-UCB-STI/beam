package beam.utils.plan_converter.entities

import java.util

import beam.utils.plan_converter.EntityTransformer

case class InputPlanElement(
  personId: Int,
  planElementIndex: Int,
  activityElement: ActivityType,
  ActivityType: Option[String],
  x: Option[Double],
  y: Option[Double],
  departureTime: Option[Double]
)

object InputPlanElement extends EntityTransformer[InputPlanElement] {
  override def transform(m: util.Map[String, String]): InputPlanElement = {
    val personId = getIfNotNull(m, "person_id").toDouble.toInt
    val planElementIndex = getIfNotNull(m, "PlanElementIndex").toDouble.toInt
    val activityElement = ActivityType.determineActivity(getIfNotNull(m, "ActivityElement"))
    val activityType = getOptional(m, "ActivityType")
    val xWgs = getOptional(m, "x").map(_.toDouble)
    val yWgs = getOptional(m, "y").map(_.toDouble)
    val departureTime = getOptional(m, "departure_time").map(_.toDouble)

    InputPlanElement(personId, planElementIndex, activityElement, activityType, xWgs, yWgs, departureTime)
  }
}
