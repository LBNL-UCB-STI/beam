package beam.router.graphhopper

import com.graphhopper.routing.util.FlagEncoder
import com.graphhopper.routing.weighting.{FastestWeighting, TurnCostProvider}
import com.graphhopper.util.EdgeIteratorState
import com.typesafe.scalalogging.LazyLogging

class BeamWeighting(flagEncoder: FlagEncoder, turnCostProvider: TurnCostProvider, wayId2TravelTime: Map[Long, Double])
    extends FastestWeighting(flagEncoder, turnCostProvider)
    with LazyLogging {

  override def getMinWeight(distance: Double): Double = super.getMinWeight(distance)

  override def calcEdgeMillis(edgeState: EdgeIteratorState, reverse: Boolean): Long = {
    calcEdgeTime(edgeState, getReverseFlag(edgeState, reverse)) match {
      case Some(value) => (1000 * value).toLong
      case None        => super.calcEdgeMillis(edgeState, reverse)
    }
  }

  def calcEdgeMillisForDetails(edgeState: EdgeIteratorState, reverse: Boolean): Long = {
    calcEdgeTime(edgeState, getReverseFlag(edgeState, reverse)) match {
      case Some(value) => (1000 * value).toLong
      case None        => super.calcEdgeMillis(edgeState, false)
    }
  }

  override def calcEdgeWeight(edgeState: EdgeIteratorState, reverse: Boolean): Double = {
    calcEdgeTime(edgeState, getReverseFlag(edgeState, reverse)).getOrElse(super.calcEdgeWeight(edgeState, reverse))
  }

  private def calcEdgeTime(edgeState: EdgeIteratorState, reverse: Boolean): Option[Double] = {
    val edgeId = if (reverse) 2 * edgeState.getEdge + 1 else 2 * edgeState.getEdge
    wayId2TravelTime.get(edgeId)
  }

  private def getReverseFlag(edgeState: EdgeIteratorState, reverse: Boolean) = {
    val reverseFlag = edgeState.get(EdgeIteratorState.REVERSE_STATE)
    if (reverse) {
      !reverseFlag
    } else {
      reverseFlag
    }

  }

  override def getName = "beam"
}
