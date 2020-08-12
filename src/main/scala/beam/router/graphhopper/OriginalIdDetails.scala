package beam.router.graphhopper

import com.graphhopper.routing.querygraph.VirtualEdgeIteratorState
import com.graphhopper.util.{EdgeIteratorState, GHUtility}
import com.graphhopper.util.details.AbstractPathDetailsBuilder

class OriginalIdDetails extends AbstractPathDetailsBuilder("original_edge_id") {
  private var edgeId = -1

  override def isEdgeDifferentToLastEdge(edge: EdgeIteratorState): Boolean = {
    val thisEdgeId = getEdgeId(edge)
    if (thisEdgeId != edgeId) {
      edgeId = thisEdgeId
      return true
    }
    false
  }

  private def getEdgeId(edge: EdgeIteratorState) = {
    edge match {
      case state: VirtualEdgeIteratorState => GHUtility.getEdgeFromEdgeKey(state.getOriginalEdgeKey)
      case _ =>
        if (edge.getAdjNode == edge.getBaseNode) {
          edge.getEdge * 2 + 1
        } else {
          edge.getEdge * 2
        }
    }
  }

  override def getCurrentValue: Object = this.edgeId.asInstanceOf[Object]
}
