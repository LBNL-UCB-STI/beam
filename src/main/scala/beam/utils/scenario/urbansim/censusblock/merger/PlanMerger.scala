package beam.utils.scenario.urbansim.censusblock.merger

import beam.utils.scenario.urbansim.censusblock.entities.InputPlanElement
import beam.utils.scenario.{PersonId, PlanElement}

class PlanMerger(modeMap: Map[String, String]) extends Merger[InputPlanElement, PlanElement] {

  def merge(inputIterator: Iterator[InputPlanElement]): Iterator[PlanElement] = inputIterator.map(transform)

  private def transform(inputPlanElement: InputPlanElement): PlanElement = {
    PlanElement(
      inputPlanElement.tripId.toString,
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
      inputPlanElement.tripMode.map(convertMode),
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
