package beam.utils.scenario.urbansim.censusblock.entities

import java.util

import beam.utils.scenario.urbansim.censusblock.EntityTransformer

case class InputPlanElement(
  tripId: Option[String],
  tourId: Option[String],
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
    val tourId = getOptional(m, "tour_id")
    val personId = getIfNotNull(m, "person_id").split("\\.").apply(0)
    val planElementIndex = getIfNotNull(m, "PlanElementIndex").toInt
    val activityElement = ActivityType.determineActivity(getIfNotNull(m, "ActivityElement"))
    val tripMode = getOptional(m, "trip_mode")
    val activityType = getOptional(m, "ActivityType")
    val xWgs = getOptional(m, "x").map(_.toDouble)
    val yWgs = getOptional(m, "y").map(_.toDouble)
    val departureTime = getOptional(m, "departure_time").map(_.toDouble)

    InputPlanElement(
      tripId,
      tourId,
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
