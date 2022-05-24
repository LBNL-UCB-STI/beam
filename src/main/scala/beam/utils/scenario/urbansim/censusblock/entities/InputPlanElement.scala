package beam.utils.scenario.urbansim.censusblock.entities

import beam.utils.scenario.urbansim.censusblock.EntityTransformer

import java.util

case class InputPlanElement(
  tripId: Option[String],
  personId: String,
  planElementIndex: Int,
  activityElement: ActivityType,
  tripMode: Option[String],
  ActivityType: Option[String],
  x: Option[Double],
  y: Option[Double],
  departureTime: Option[Double]
)

object InputPlanElement extends EntityTransformer[InputPlanElement] {

  override def transform(m: util.Map[String, String]): InputPlanElement = {
    val tripId = getOptional(m, "trip_id")
    val personId = getIfNotNull(m, "person_id").split("\\.").apply(0)
    val planElementIndex = getIfNotNull(m, "PlanElementIndex").toInt
    val activityElement = ActivityType.determineActivity(getIfNotNull(m, "ActivityElement"))
    val tripMode = getOptional(m, "trip_mode")
    val activityType = getOptional(m, "ActivityType")
    val xWgs = getOptional(m, "x").map(_.toDouble)
    val yWgs = getOptional(m, "y").map(_.toDouble)
    val departureTime =
      try { getOptional(m, "departure_time").map(_.toDouble) }
      catch { case _: Throwable => None }

    InputPlanElement(
      tripId,
      personId,
      planElementIndex,
      activityElement,
      tripMode,
      activityType,
      xWgs,
      yWgs,
      departureTime
    )
  }
}
