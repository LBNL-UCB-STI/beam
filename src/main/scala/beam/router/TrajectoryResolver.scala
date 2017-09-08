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

class StreetPathTrajectoryResolver(@transient streetLayer: StreetLayer, tripStartTime: Long, duration: Int) extends TrajectoryResolver {
  override def resolve(beamPath: BeamPath): Trajectory = {
    val first = streetLayer.edgeStore.getCursor(beamPath.linkIds.head.toInt)
    val firstPoint  = SpaceTime(first.getGeometry.getCoordinate.x, first.getGeometry.getCoordinate.y, tripStartTime)
    val last = streetLayer.edgeStore.getCursor(beamPath.linkIds.last.toInt)
    val lastPoint  = SpaceTime(last.getGeometry.getCoordinate.x, last.getGeometry.getCoordinate.y, tripStartTime + duration)
    //XXX: let Trajectory logic interpolate intermediate points
    Trajectory(Vector(firstPoint, lastPoint))
  }
}

class TrajectoryByEdgeIdsResolver(@transient streetLayer: StreetLayer) extends TrajectoryResolver {

  override def resolve(beamPath: BeamPath): Trajectory = {
    val path = beamPath.linkIds.map(_.toInt).flatMap { edgeId =>
      val edge = streetLayer.edgeStore.getCursor(edgeId)
      //TODO: resolve time from stopinfo and tripSchedule(for transit), linkid -> stop is one -> one: links.zip(stops)....
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
