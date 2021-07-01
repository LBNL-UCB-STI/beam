package beam.utils.scenario.urbansim.censusblock.merger

import beam.utils.scenario.urbansim.censusblock.entities.{Activity, InputPlanElement, Leg}
import beam.utils.scenario.{PersonId, PlanElement}

object PlanMerger {

  def tripKey(person: String, time: Double): (String, Double) = {
    (person, math.floor(time))
  }
}

class PlanMerger(val trips: Map[(String, Double), String], modeMap: Map[String, String])
    extends Merger[InputPlanElement, PlanElement] {

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

  private def convertMode(inputMode: String): String = modeMap(inputMode)
}
