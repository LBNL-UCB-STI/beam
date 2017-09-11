package beam.router

import beam.agentsim.agents.vehicles.Trajectory
import beam.agentsim.events.SpaceTime
import beam.router.RoutingModel.BeamPath
import com.conveyal.r5.api.util.StreetSegment
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

/**
  * Resolve trajectory by predifined street segment and tripStartTime assuming uniform movement,
  * This is used by leg generation logic and beam paths shouldn't contain lot of links - in most cases 1-3 links.
  * Thus is safe to assume uniform movement.
  *
  * @param streetSegment street segment wih defined geometry
  * @param tripStartTime when object start moving over this segment
  */
class StreetSegmentTrajectoryResolver(streetSegment: StreetSegment, tripStartTime: Long) extends TrajectoryResolver {
  override def resolve(beamPath: BeamPath): Trajectory = {
    val checkpoints = streetSegment.geometry.getCoordinates
    val timeDelta = streetSegment.duration.toDouble / checkpoints.length
    val path = checkpoints.zipWithIndex.map{ case (coord, i) =>
        SpaceTime(coord.x, coord.y, (tripStartTime + i*timeDelta).toLong)
    }
    //XXX: let Trajectory logic interpolate intermediate points
    Trajectory(path.toVector)
  }
}

class TrajectoryByEdgeIdsResolver(@transient streetLayer: StreetLayer, departure: Long, duration: Long) extends TrajectoryResolver {

  override def resolve(beamPath: BeamPath): Trajectory = {
    val stepDelta = duration.toDouble / beamPath.linkIds.size
    val path = beamPath.linkIds.filter(!_.equals("")).map(_.toInt).zipWithIndex.flatMap { case (edgeId, i) =>
      val edge = streetLayer.edgeStore.getCursor(edgeId)
      //TODO: resolve time from stopinfo and tripSchedule(for transit), linkid -> stop is one -> one: links.zip(stops)....
      val time = departure  + (i * stepDelta).toLong
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
