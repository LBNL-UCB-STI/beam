package beam.router.graphhopper

import com.graphhopper.routing.weighting.Weighting
import com.graphhopper.util.details.AbstractPathDetailsBuilder
import com.graphhopper.util.{EdgeIteratorState, GHUtility}

import scala.util.Try

/**
  * It's custom implementation of TimeDetails
  */
abstract class AbstractBeamTimeDetails(val weighting: Weighting, name: String, val reverse: Boolean)
    extends AbstractPathDetailsBuilder(name) {

  private var prevEdgeId = -1
  // will include the turn time penalty
  private var time = 0.0D

  override def isEdgeDifferentToLastEdge(edge: EdgeIteratorState): Boolean = {
    if (edge.getEdge != prevEdgeId) {
      time = (weighting match {
        case bWeighting: BeamWeighting =>
          Try(bWeighting.calcEdgeMillisForDetails(edge, reverse))
            .getOrElse(getDefaultTime(edge))
        case _ => getDefaultTime(edge)
      }).toDouble / 1000.0

      prevEdgeId = edge.getEdge
      true
    } else {
      false
    }
  }

  private def getDefaultTime(edge: EdgeIteratorState) =
    GHUtility.calcMillisWithTurnMillis(weighting, edge, false, prevEdgeId)

  override def getCurrentValue: Object = time.asInstanceOf[Object]
}
