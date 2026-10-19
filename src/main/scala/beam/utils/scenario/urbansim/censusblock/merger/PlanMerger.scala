package beam.utils.scenario.urbansim.censusblock.merger

import beam.utils.scenario.urbansim.censusblock.entities.InputPlanElement
import beam.utils.scenario.{PersonId, PlanElement}

class PlanMerger(modeMap: Map[String, String]) extends Merger[InputPlanElement, PlanElement] {

  def merge(inputIterator: Iterator[InputPlanElement]): Iterator[PlanElement] = inputIterator.map(transform)

  private def transform(inputPlanElement: InputPlanElement): PlanElement = {
    val planIndex = inputPlanElement.planIndex.getOrElse(0)
    PlanElement(
      inputPlanElement.tripId.getOrElse(""),
      inputPlanElement.tourId.getOrElse(""),
      PersonId(inputPlanElement.personId),
      planIndex,
      inputPlanElement.planScore.getOrElse(0.0),
      planSelected = inputPlanElement.planSelected.getOrElse(planIndex == 0),
      PlanElement.PlanElementType(inputPlanElement.activityElement.toString),
      inputPlanElement.planElementIndex,
      inputPlanElement.ActivityType,
      inputPlanElement.x,
      inputPlanElement.y,
      inputPlanElement.departureTime,
      inputPlanElement.tripMode.map(convertMode),
      legDepartureTime = None,
      legTravelTime = None,
      legExpectedTravelTime = inputPlanElement.expectedDurationMinutes,
      legExpectedCost = inputPlanElement.expectedCostDollars,
      legRouteType = None,
      legRouteStartLink = None,
      legRouteEndLink = None,
      legRouteTravelTime = None,
      legRouteDistance = None,
      legRouteLinks = Seq.empty,
      geoId = None
    )
  }

  private def convertMode(inputMode: String): String = modeMap.getOrElse(inputMode, inputMode)
}
