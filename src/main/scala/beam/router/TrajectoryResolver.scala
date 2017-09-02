package beam.router

import beam.agentsim.agents.vehicles.Trajectory
import beam.agentsim.events.SpaceTime
import beam.router.RoutingModel.BeamPath
import com.conveyal.r5.streets.StreetLayer

/**
  * Created by dserdiuk on 9/2/17.
  */
trait TrajectoryResolver {

  def resolve(beamPath: BeamPath): Trajectory
}

object EmptyTrajectoryResolver extends TrajectoryResolver {
  override def resolve(beamPath: BeamPath): Trajectory = {
    Trajectory(Vector())
  }
}


class TrajectoryByEdgeIdsResolver(@transient streetLayer: StreetLayer) extends TrajectoryResolver {

  override def resolve(beamPath: BeamPath): Trajectory = {
    val path = beamPath.linkIds.map(_.toInt).flatMap { edgeId =>
      val edge = streetLayer.edgeStore.getCursor(edgeId)
      //TODO: do we need to do coordinate transformation here
      //TODO: resolve from stopinfo and tripSchedule, linkid -> stop is one -> one: links.zip(stops)....
      val time = -1
      edge.getGeometry.getCoordinates.map(coord => SpaceTime(coord.x, coord.y, time))
    }
    Trajectory(path)
  }
}

class DefinedTrajectoryHolder(trajectory: Trajectory) extends TrajectoryResolver {
  override def resolve(beamPath: BeamPath): Trajectory = {
    require(beamPath.resolver == this, "Wrong beam path")
    trajectory
  }
}
