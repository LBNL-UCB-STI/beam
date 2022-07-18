package beam.utils.scenario.urbansim.censusblock.merger

import beam.utils.logging.ExponentialLazyLogging
import beam.utils.scenario.urbansim.censusblock.entities.InputPlanElement
import beam.utils.scenario.{PersonId, PlanElement}

class PlanMerger(modeMap: Map[String, String])
    extends Merger[InputPlanElement, PlanElement]
    with ExponentialLazyLogging {

  def merge(inputIterator: Iterator[InputPlanElement]): Iterator[PlanElement] = inputIterator.map(transform)

  private def transform(inputPlanElement: InputPlanElement): PlanElement = {
    PlanElement(
      inputPlanElement.tripId.getOrElse(""),
      PersonId(inputPlanElement.personId),
      0,
      0,
      planSelected = true,
      PlanElement.PlanElementType(inputPlanElement.activityElement.toString),
      inputPlanElement.planElementIndex,
      inputPlanElement.ActivityType,
      inputPlanElement.x,
      inputPlanElement.y,
      activityStartTime = None,
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

  private def convertMode(inputMode: String): String = {
    modeMap.get(inputMode) match {
      case Some(convertedMode) => convertedMode
      case None =>
        logger.warn(s"beam.exchange.scenario.modeMap does not contain $inputMode, using $inputMode as return value.")
        inputMode
    }
  }
}
