package beam.utils.scenario.urbansim.censusblock.entities

import java.util

import beam.utils.scenario.urbansim.censusblock.EntityTransformer

case class InputPlanElement(
  personId: String,
  planElementIndex: Int,
  activityElement: ActivityType,
  ActivityType: Option[String],
  x: Option[Double],
  y: Option[Double],
  departureTime: Option[Double]
)

object InputPlanElement extends EntityTransformer[InputPlanElement] {
  override def transform(m: util.Map[String, String]): InputPlanElement = {
    val personId = getIfNotNull(m, "person_id").split("\\.").apply(0)
    val planElementIndex = getIfNotNull(m, "PlanElementIndex").toInt
    val activityElement = ActivityType.determineActivity(getIfNotNull(m, "ActivityElement"))
    val activityType = getOptional(m, "ActivityType")
    val xWgs = getOptional(m, "x").map(_.toDouble)
    val yWgs = getOptional(m, "y").map(_.toDouble)
    val departureTime = getOptional(m, "departure_time").map(_.toDouble)

    InputPlanElement(personId, planElementIndex, activityElement, activityType, xWgs, yWgs, departureTime)
  }
}
