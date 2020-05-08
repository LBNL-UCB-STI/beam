package beam.utils.plan_converter

import scala.math._

class Merger(val trips: Map[(Int, Double), String]) {

  private var activityPersonOpt: Option[Int] = None
  private var timeOpt: Option[Double] = None

  def merge(inputIterator: Iterator[InputPlanElement]): Iterator[OutputPlanElement] =
    new Iterator[OutputPlanElement] {
      override def hasNext: Boolean = inputIterator.hasNext

      override def next(): OutputPlanElement = transform(inputIterator.next())
    }

  private def transform(inputPlanElement: InputPlanElement): OutputPlanElement =
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

  private def inputToOutput(inputPlanElement: InputPlanElement, mode: Option[String]): OutputPlanElement = {
    OutputPlanElement(
      inputPlanElement.personId,
      inputPlanElement.activityElement,
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
    case "SHARED3FREE"    => ""
    case "SHARED2PAY"     => ""
    case "SHARED2FREE"    => ""
    case "SHARED3PAY"     => ""
    case "WALK_LOC"       => "walk_transit"
    case "DRIVE_LOC"      => "drive_transit"
  }
}
