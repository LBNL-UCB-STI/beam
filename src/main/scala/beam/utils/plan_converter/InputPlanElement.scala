package beam.utils.plan_converter

import scala.annotation.switch

sealed trait ActivityType
case object Activity extends ActivityType
case object Leg extends ActivityType

object ActivityType {

  def determineActivity(activityName: String): ActivityType = (activityName: @switch) match {
    case "activity" => Activity
    case "leg"      => Leg
  }
}

case class InputPlanElement(
  personId: Int,
  planElementIndex: Int,
  activityElement: String,
  ActivityType: ActivityType,
  x: Double,
  y: Double,
  departureTime: Double
)
