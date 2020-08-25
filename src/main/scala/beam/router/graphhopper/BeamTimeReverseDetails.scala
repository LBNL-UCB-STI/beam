package beam.router.graphhopper

import com.graphhopper.routing.ev.UnsignedIntEncodedValue
import com.graphhopper.routing.weighting.Weighting
import com.graphhopper.util.details.AbstractPathDetailsBuilder
import com.graphhopper.util.{EdgeIteratorState, GHUtility}

class BeamTimeReverseDetails(val weighting: Weighting) extends AbstractPathDetailsBuilder(BeamTimeReverseDetails.BEAM_REVERSE_TIME) {

  private var prevEdgeId = -1
  // will include the turn time penalty
  private var time = 0L

  override def isEdgeDifferentToLastEdge(edge: EdgeIteratorState): Boolean = {
    if (edge.getEdge != prevEdgeId) {
      time = try {
        weighting match {
          case bWeighting: BeamWeighting => bWeighting.calcEdgeMillisForDetails(edge, reverse = true)
          case _ => getDefaultTime(edge)
        }
      } catch {
        case _: Exception => getDefaultTime(edge)
      }

      prevEdgeId = edge.getEdge
      true
    } else {
      false
    }
  }

  private def getDefaultTime(edge: EdgeIteratorState) = GHUtility.calcMillisWithTurnMillis(weighting, edge, false, prevEdgeId)

  override def getCurrentValue: Object = time.asInstanceOf[Object]
}

object BeamTimeReverseDetails {
  val BEAM_REVERSE_TIME = "beam_time_reverse"
  val BEAM_INVERTED_REVERSE_STATE = new UnsignedIntEncodedValue("beam_reverse", 2, false)
}