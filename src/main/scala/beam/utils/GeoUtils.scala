package beam.utils

import com.conveyal.r5.transit.TransportNetwork
import org.matsim.api.core.v01.Coord

object GeoUtils {

  def getR5EdgeCoord(linkIdInt: Int, transportNetwork: TransportNetwork): Coord = {
    val currentEdge = transportNetwork.streetLayer.edgeStore.getCursor(linkIdInt)
    new Coord(currentEdge.getGeometry.getCoordinate.x, currentEdge.getGeometry.getCoordinate.y)
  }
}
