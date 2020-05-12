package beam.utils.plan_converter.merger

import beam.utils.plan_converter.entities.{Activity, InputPlanElement, Leg}
import beam.utils.plan_converter.Leg
import beam.utils.scenario.{PersonId, PlanElement}

import scala.math._

class PlanMerger(val trips: Map[(Int, Double), String]) extends Merger[InputPlanElement, PlanElement] {

  private var activityPersonOpt: Option[Int] = None
  private var timeOpt: Option[Double] = None

  def merge(inputIterator: Iterator[InputPlanElement]): Iterator[PlanElement] =
    new Iterator[PlanElement] {
      override def hasNext: Boolean = inputIterator.hasNext

      override def next(): PlanElement = transform(inputIterator.next())
    }

  private def transform(inputPlanElement: InputPlanElement): PlanElement =
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

  private def inputToOutput(inputPlanElement: InputPlanElement, mode: Option[String]): PlanElement = {

    val personId = PersonId(inputPlanElement.personId.toString)

    PlanElement(
      personId,
      0, //TODO: question it
      0d, //TODO: question it
      true, //TODO: question it
      inputPlanElement.activityElement.toString,
      inputPlanElement.planElementIndex,
      inputPlanElement.ActivityType,
      inputPlanElement.x,
      inputPlanElement.y,
      inputPlanElement.departureTime,
      mode,
      legDepartureTime = None,
      legTravelTime = None,
      legRouteType = None,
      legRouteStartLink = None,
      legRouteEndLink = None,
      legRouteTravelTime = None,
      legRouteDistance = None,
      legRouteLinks = Seq.empty
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
