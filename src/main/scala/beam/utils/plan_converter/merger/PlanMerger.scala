package beam.utils.plan_converter.merger

import beam.utils.plan_converter.entities.{Activity, InputPlanElement, Leg}
import beam.utils.plan_converter.Leg
import beam.utils.scenario.urbansim.DataExchange
import beam.utils.scenario.{PersonId, PlanElement}

import scala.math._

class PlanMerger(val trips: Map[(Int, Double), String]) extends Merger[InputPlanElement, DataExchange.PlanElement] {

  private var activityPersonOpt: Option[Int] = None
  private var timeOpt: Option[Double] = None

  def merge(inputIterator: Iterator[InputPlanElement]): Iterator[DataExchange.PlanElement] =
    new Iterator[DataExchange.PlanElement] {
      override def hasNext: Boolean = inputIterator.hasNext

      override def next(): DataExchange.PlanElement = transform(inputIterator.next())
    }

  private def transform(inputPlanElement: InputPlanElement): DataExchange.PlanElement =
    inputPlanElement.activityElement match {
      case Activity =>
        activityPersonOpt = Some(inputPlanElement.personId)
        timeOpt = inputPlanElement.departureTime
        inputToOutput(inputPlanElement, None)
      case Leg =>
        val modeOpt = for {
          activityPerson <- activityPersonOpt
          time           <- timeOpt.map(floor)
          inputRes       <- trips.get((activityPerson, time))
          outputRes = convertMode(inputRes)
        } yield outputRes

        activityPersonOpt = None
        timeOpt = None

        inputToOutput(inputPlanElement, modeOpt)
    }

  private def inputToOutput(inputPlanElement: InputPlanElement, mode: Option[String]): DataExchange.PlanElement = {

    DataExchange.PlanElement(
      inputPlanElement.personId.toString,
      inputPlanElement.activityElement.toString,
      inputPlanElement.planElementIndex,
      inputPlanElement.ActivityType,
      inputPlanElement.x,
      inputPlanElement.y,
      inputPlanElement.departureTime,
      mode
    )
  }

  private def convertMode(inputMode: String): String = inputMode match {
    case "DRIVEALONEPAY"  => "car"
    case "DRIVEALONEFREE" => "car"
    case "WALK"           => "walk"
    case "BIKE"           => "bike"
    case "SHARED3FREE"    => "car"
    case "SHARED2PAY"     => "car"
    case "SHARED2FREE"    => "car"
    case "SHARED3PAY"     => "car"
    case "WALK_LOC"       => "walk_transit"
    case "DRIVE_LOC"      => "drive_transit"
  }
}
