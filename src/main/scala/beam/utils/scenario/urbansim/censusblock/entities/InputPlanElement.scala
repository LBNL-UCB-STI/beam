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
  planSelected: Option[Boolean] = None,
  legDepartureTime: Option[String] = None,
  legTravelTime: Option[String] = None
)

object InputPlanElement extends EntityTransformer[InputPlanElement] {

  def parseBoolean(s: String): Option[Boolean] = {
    s.trim.toLowerCase match {
      case "true" | "t" | "1" | "1.0"  => Some(true)
      case "false" | "f" | "0" | "0.0" => Some(false)
      case other                       => scala.util.Try(other.toBoolean).toOption
    }
  }

  private def isNullOrNanString(str: String): Boolean =
    str.isEmpty || str.equalsIgnoreCase("nan") || str.equalsIgnoreCase("null") || str.equalsIgnoreCase("none")

  private def getOptionalNonEmpty(m: util.Map[String, String], key: String): Option[String] =
    getOptional(m, key).map(_.trim).filter(_.nonEmpty)

  private def getFromNames(m: util.Map[String, String], names: String*): Option[String] =
    names.iterator.flatMap(name => getOptionalNonEmpty(m, name)).find(_.nonEmpty)

  override def transform(m: util.Map[String, String]): InputPlanElement = {
    val tripId = getFromNames(m, "trip_id", "tripId").filterNot(isNullOrNanString)
    val tourId = getFromNames(m, "tour_id", "tourId").filterNot(isNullOrNanString)
    val personId = getFromNames(m, "person_id", "personId")
      .map(_.split("\\.")(0))
      .getOrElse(throw new IllegalArgumentException(s"Missing person_id in record: $m"))
    val planElementIndex = getFromNames(m, "PlanElementIndex", "planElementIndex")
      .flatMap(s => scala.util.Try(s.toDouble.toInt).toOption)
      .getOrElse(throw new IllegalArgumentException(s"Missing planElementIndex in record: $m"))
    val activityElement = getFromNames(m, "ActivityElement", "activityElement", "planElementType")
      .map(ActivityType.determineActivity)
      .getOrElse(throw new IllegalArgumentException(s"Missing activityElement in record: $m"))
    val tripMode = getFromNames(m, "trip_mode", "legMode").filterNot(isNullOrNanString)
    val activityType = getFromNames(m, "ActivityType", "activityType").filterNot(isNullOrNanString)
    val xWgs = getFromNames(m, "x", "activityLocationX")
      .filterNot(isNullOrNanString)
      .flatMap(s => scala.util.Try(s.toDouble).toOption)
    val yWgs = getFromNames(m, "y", "activityLocationY")
      .filterNot(isNullOrNanString)
      .flatMap(s => scala.util.Try(s.toDouble).toOption)
    val departureTime = getFromNames(m, "departure_time", "departureTime", "activityEndTime")
      .filterNot(isNullOrNanString)
      .flatMap(s => scala.util.Try(s.toDouble).toOption)
    val legDepartureTime =
      getFromNames(m, "legDepartureTime", "leg_departure_time", "legdeparturetime").filterNot(isNullOrNanString)
    val legTravelTime =
      getFromNames(m, "legTravelTime", "leg_travel_time", "legtraveltime").filterNot(isNullOrNanString)
    val expectedDurationMinutes =
      getFromNames(m, "trip_dur_min").filterNot(isNullOrNanString).flatMap(s => scala.util.Try(s.toDouble).toOption)
    val expectedCostDollars =
      getFromNames(m, "trip_cost_dollars")
        .filterNot(isNullOrNanString)
        .flatMap(s => scala.util.Try(s.toDouble).toOption)
    val planIndex = getFromNames(m, "planIndex", "planindex")
      .filterNot(isNullOrNanString)
      .flatMap(s => scala.util.Try(s.toDouble.toInt).toOption)
    val planScore = getFromNames(m, "planScore", "planscore").flatMap { s =>
      val str = s.trim
      if (str.isEmpty || str.equalsIgnoreCase("null") || str.equalsIgnoreCase("none")) None
      else if (str.equalsIgnoreCase("nan")) Some(Double.NaN)
      else scala.util.Try(str.toDouble).toOption
    }
    val planSelected = getFromNames(m, "planSelected", "planselected")
      .filterNot(isNullOrNanString)
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
      planSelected,
      legDepartureTime,
      legTravelTime
    )
  }
}
