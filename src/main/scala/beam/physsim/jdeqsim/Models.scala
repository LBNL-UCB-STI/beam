package beam.physsim.jdeqsim

import beam.router.BeamRouter.RoutingResponse
import beam.utils.Statistics
import org.matsim.analysis.VolumesAnalyzer
import org.matsim.api.core.v01.population.Leg
import org.matsim.core.router.util.TravelTime

import scala.util.Try

private[jdeqsim] case class ElementIndexToLeg(index: Int, leg: Leg)
private[jdeqsim] case class ElementIndexToRoutingResponse(index: Int, routingResponse: Try[RoutingResponse])
private[jdeqsim] case class RerouteStats(nRoutes: Int, totalRouteLen: Double, totalLinkCount: Int)

case class SimulationResult(
  iteration: Int,
  travelTime: TravelTime,
  volumesAnalyzer: Option[VolumesAnalyzer],
  eventTypeToNumberOfMessages: Seq[(String, Long)],
  carTravelTimeStats: Statistics
)
