package beam.utils.scenario.urbansim.censusblock.entities

import scala.annotation.switch

sealed trait ActivityType
case object Activity extends ActivityType {
  override def toString: String = "activity"
}
case object Leg extends ActivityType {
  override def toString: String = "leg"
}

object ActivityType {

  def determineActivity(activityName: String): ActivityType = (activityName: @switch) match {
    case "activity" => Activity
    case "leg"      => Leg
  }
}
