package beam.utils.scenario.urbansim.censusblock.merger

import beam.utils.scenario.urbansim.censusblock.entities.{Activity, InputPlanElement, Leg}
import beam.utils.scenario.{PersonId, PlanElement}

object PlanMerger {

  def tripKey(person: String, time: Double): (String, Double) = {
    (person, math.floor(time))
  }
}

class PlanMerger(val trips: Map[(String, Double), String]) extends Merger[InputPlanElement, PlanElement] {

  import PlanMerger._

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
          time           <- timeOpt
          inputRes       <- trips.get(tripKey(activityPerson, time))
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
      PlanElement.PlanElementType(inputPlanElement.activityElement.toString),
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
//    case "HOV2"           => "hov2_teleportation"
//    case "HOV3"           => "hov3_teleportation"
//    case "HOV2"           => "car_hov2"
//    case "HOV3"           => "car_hov3"
    case "HOV2"           => "car"
    case "HOV3"           => "car"
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
