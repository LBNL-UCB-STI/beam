package beam.utils.scenario.urbansim.censusblock.entities

import com.univocity.parsers.common.record.Record

import beam.utils.scenario.urbansim.censusblock.EntityTransformer

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

  override def transform(m: Record): InputPlanElement = {
    val tripId = getStringOptional(m, "trip_id")
    val personId = getStringIfNotNull(m, "person_id").split("\\.").apply(0)
    val planElementIndex = getIntIfNotNull(m, "PlanElementIndex")
    val activityElement = ActivityType.determineActivity(getStringIfNotNull(m, "ActivityElement"))
    val tripMode = getStringOptional(m, "trip_mode")
    val activityType = getStringOptional(m, "ActivityType")
    val xWgs = getDoubleOptional(m, "x")
    val yWgs = getDoubleOptional(m, "y")
    val departureTime = getDoubleOptional(m, "departure_time")

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
