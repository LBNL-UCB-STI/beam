package beam.router.graphhopper

import com.graphhopper.routing.util.FlagEncoder
import com.graphhopper.routing.weighting.{FastestWeighting, TurnCostProvider}
import com.graphhopper.util.{EdgeIteratorState, FetchMode}
import com.typesafe.scalalogging.LazyLogging
import org.locationtech.jts.geom.Coordinate
import org.matsim.api.core.v01.Coord
import org.matsim.api.core.v01.network.Link

class BeamWeighting(flagEncoder: FlagEncoder, turnCostProvider: TurnCostProvider, wayId2TravelTime: Map[Long, Double])
    extends FastestWeighting(flagEncoder, turnCostProvider)
    with LazyLogging {

  override def getMinWeight(distance: Double): Double = super.getMinWeight(distance)

  override def calcEdgeMillis(edgeState: EdgeIteratorState, reverse: Boolean): Long = {
    calcEdgeTime(edgeState, reverse) match {
      case Some(value) => (1000 * value).toLong
      case None        => super.calcEdgeMillis(edgeState, reverse)
    }
  }

  override def calcEdgeWeight(edgeState: EdgeIteratorState, reverse: Boolean): Double = {
    calcEdgeTime(edgeState, reverse).getOrElse(super.calcEdgeWeight(edgeState, reverse))
  }

  private def calcEdgeTime(edgeState: EdgeIteratorState, reverse: Boolean): Option[Double] = {
    val edgeId = if (reverse) 2 * edgeState.getEdge + 1 else 2 * edgeState.getEdge
    wayId2TravelTime.get(edgeId)
  }

  override def getName = "beam"
}
