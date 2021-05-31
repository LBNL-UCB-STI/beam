package beam.utils.scenario.urbansim.censusblock.merger

import beam.utils.scenario.urbansim.censusblock.entities.{Activity, InputPlanElement, Leg}
import beam.utils.scenario.{PersonId, PlanElement}

import scala.math._

class PlanMerger(val trips: Map[(String, Double), String]) extends Merger[InputPlanElement, PlanElement] {

  private var activityPersonOpt: Option[String] = None
  private var timeOpt: Option[Double] = None

  def merge(inputIterator: Iterator[InputPlanElement]): Iterator[PlanElement] = inputIterator.map(transform)

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
    PlanElement(
      PersonId(inputPlanElement.personId),
      0,
      0,
      planSelected = true,
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
      legRouteLinks = Seq.empty,
      geoId = None
    )
  }

  private def convertMode(inputMode: String): String = inputMode match {
    case "BIKE"           => "bike"
    case "DRIVEALONEFREE" => "car"
    case "DRIVEALONEPAY"  => "car"
    case "DRIVE_COM"      => "car" // "drive_transit" ??
    case "DRIVE_EXP"      => "car" // "drive_transit" ??
    case "DRIVE_HVY"      => "car" // "drive_transit" ??
    case "DRIVE_LOC"      => "car" // "drive_transit" ??
    case "DRIVE_LRF"      => "car" // "drive_transit" ??
    case "SHARED2FREE"    => "car"
    case "SHARED2PAY"     => "car"
    case "SHARED3FREE"    => "car"
    case "SHARED3PAY"     => "car"
    case "TAXI"           => "ride_hail"
    case "TNC_SHARED"     => "ride_hail"
    case "TNC_SINGLE"     => "ride_hail"
    case "WALK"           => "walk"
    case "WALK_COM"       => "rail" // "walk_transit" ??
    case "WALK_EXP"       => "rail" // "walk_transit" ??
    case "WALK_HVY"       => "rail" // "walk_transit" ??
    case "WALK_LOC"       => "bus" // "walk_transit" ??
    case "WALK_LRF"       => "rail" // "walk_transit" ??
  }
}
