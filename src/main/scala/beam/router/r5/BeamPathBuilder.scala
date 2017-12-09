package beam.router.r5

import java.util
import java.util.Collections

import beam.router.RoutingModel.{BeamPath, WindowTime}
import beam.router.{StreetSegmentTrajectoryResolver, TrajectoryByEdgeIdsResolver}
import beam.sim.BeamServices
import com.conveyal.r5.api.util._
import com.conveyal.r5.point_to_point.builder.PointToPointQuery
import com.conveyal.r5.profile.{ProfileRequest, StreetMode}
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.api.core.v01.Coord
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._



object BeamPathBuilder {
  private val log  =LoggerFactory.getLogger(classOf[BeamPathBuilder])

}

class BeamPathBuilder(transportNetwork: TransportNetwork, beamServices: BeamServices) {


  import BeamPathBuilder._


}
