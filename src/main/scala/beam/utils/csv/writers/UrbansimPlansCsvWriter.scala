package beam.utils.csv.writers

import beam.utils.csv.writers.ScenarioCsvWriter._
import beam.utils.scenario.PlanElement
import org.matsim.api.core.v01.Scenario

object UrbansimPlansCsvWriter extends ScenarioCsvWriter {

  override protected val fields: Seq[String] = Seq(
    "trip_id",
    "person_id",
    "PlanElementIndex",
    "ActivityElement",
    "trip_mode",
    "ActivityType",
    "x",
    "y",
    "departure_time"
  )

  override def contentIterator(scenario: Scenario): Iterator[String] = ???

  override def contentIterator[A](elements: Iterator[A]): Iterator[String] = {
    elements.flatMap {
      case planInfo: PlanElement => Some(toLine(planInfo))
      case _                     => None
    }
  }

  private def toLine(planInfo: PlanElement): String = {
    Seq(
      planInfo.tripId,
      planInfo.personId.id,
      planInfo.planElementIndex,
      planInfo.planElementType.toString,
      planInfo.legMode.getOrElse(""),
      planInfo.activityType.getOrElse(""),
      planInfo.activityLocationX.map(_.toString).getOrElse(""),
      planInfo.activityLocationY.map(_.toString).getOrElse(""),
      planInfo.activityEndTime.map(_.toString).getOrElse("")
    ).mkString("", FieldSeparator, LineSeparator)
  }

}
