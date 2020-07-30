package beam.router.graphhopper

import com.graphhopper.routing.util.FlagEncoder
import com.graphhopper.routing.weighting.FastestWeighting
import com.graphhopper.util.{EdgeIteratorState, FetchMode}
import com.typesafe.scalalogging.LazyLogging
import org.locationtech.jts.geom.Coordinate
import org.matsim.api.core.v01.Coord
import org.matsim.api.core.v01.network.Link

class BeamWeighting(flagEncoder: FlagEncoder, wayId2TravelTime: Map[Long, Double])
    extends FastestWeighting(flagEncoder)
    with LazyLogging {

  override def getMinWeight(distance: Double): Double = super.getMinWeight(distance)

  override def calcEdgeMillis(edgeState: EdgeIteratorState, reverse: Boolean): Long =
    super.calcEdgeMillis(edgeState, reverse)

  override def calcEdgeWeight(edgeState: EdgeIteratorState, reverse: Boolean): Double = {
    val nodeGeom = edgeState.fetchWayGeometry(FetchMode.ALL).toLineString(false)

    val time = for {
      link <- wayId2TravelTime.get(
        toCoord(nodeGeom.getCoordinateN(0)),
        toCoord(nodeGeom.getCoordinateN(nodeGeom.getNumPoints - 1))
      )
      travelTime <- link2TravelTime.get(link)
    } yield {
      travelTime
    }

    time.getOrElse {
      logger.info("FUCK!")
      super.calcEdgeWeight(edgeState, reverse)
    }
  }

  private def toCoord(coord: Coordinate): Coord = new Coord(coord.x, coord.y)
}
