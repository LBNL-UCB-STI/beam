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
  departureTime: Option[Double],
  expectedDurationMinutes: Option[Double] = None,
  expectedCostDollars: Option[Double] = None,
  planIndex: Option[Int] = None,
  planScore: Option[Double] = None,
  planSelected: Option[Boolean] = None
)

object InputPlanElement extends EntityTransformer[InputPlanElement] {

  def parseBoolean(s: String): Option[Boolean] = {
    s.trim.toLowerCase match {
      case "true" | "t" | "1" | "1.0"  => Some(true)
      case "false" | "f" | "0" | "0.0" => Some(false)
      case other                       => scala.util.Try(other.toBoolean).toOption
    }
  }

  private def getOptionalNonEmpty(m: util.Map[String, String], key: String): Option[String] =
    getOptional(m, key).map(_.trim).filter(_.nonEmpty)

  override def transform(m: util.Map[String, String]): InputPlanElement = {
    val tripId = getOptional(m, "trip_id")
    val tourId = getOptional(m, "tour_id")
    val personId = getIfNotNull(m, "person_id").split("\\.")(0)
    val planElementIndex = getIfNotNull(m, "PlanElementIndex").toInt
    val activityElement = ActivityType.determineActivity(getIfNotNull(m, "ActivityElement"))
    val tripMode = getOptional(m, "trip_mode")
    val activityType = getOptional(m, "ActivityType")
    val xWgs = getOptionalNonEmpty(m, "x").map(_.toDouble)
    val yWgs = getOptionalNonEmpty(m, "y").map(_.toDouble)
    val departureTime = getOptionalNonEmpty(m, "departure_time").map(_.toDouble)
    val expectedDurationMinutes =
      getOptionalNonEmpty(m, "trip_dur_min").flatMap(s => scala.util.Try(s.toDouble).toOption)
    val expectedCostDollars =
      getOptionalNonEmpty(m, "trip_cost_dollars").flatMap(s => scala.util.Try(s.toDouble).toOption)
    val planIndex = getOptionalNonEmpty(m, "planIndex")
      .orElse(getOptionalNonEmpty(m, "planindex"))
      .flatMap(s => scala.util.Try(s.toDouble.toInt).toOption)
    val planScore = getOptionalNonEmpty(m, "planScore")
      .orElse(getOptionalNonEmpty(m, "planscore"))
      .flatMap(s => scala.util.Try(s.toDouble).toOption)
    val planSelected = getOptionalNonEmpty(m, "planSelected")
      .orElse(getOptionalNonEmpty(m, "planselected"))
      .flatMap(parseBoolean)

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
      departureTime,
      expectedDurationMinutes,
      expectedCostDollars,
      planIndex,
      planScore,
      planSelected
    )
  }
}
