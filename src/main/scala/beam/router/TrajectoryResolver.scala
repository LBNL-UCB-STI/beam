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

  def resolveStart(beamPath: BeamPath): SpaceTime

  def resolveEnd(beamPath: BeamPath): SpaceTime

  def resolveLengthInM(beamPath: BeamPath): Double
}

object EmptyTrajectoryResolver extends TrajectoryResolver {
  override def resolve(beamPath: BeamPath): Trajectory = {
    Trajectory(Vector())
  }

  override def resolveStart(beamPath: BeamPath): SpaceTime = SpaceTime.zero

  override def resolveEnd(beamPath: BeamPath): SpaceTime = SpaceTime.zero

  override def resolveLengthInM(beamPath: BeamPath): Double = 0.0
}

/**
  * Resolve trajectory by predifined street segment and tripStartTime assuming uniform movement,
  * This is used by leg generation logic and beam paths shouldn't contain lot of links - in most cases 1-3 links.
  * Thus is safe to assume uniform movement.
  *
  * @param streetSegment street segment wih defined geometry
  * @param tripStartTime when object start moving over this segment
  */
case class StreetSegmentTrajectoryResolver(streetSegment: StreetSegment, tripStartTime: Long) extends TrajectoryResolver {

  override def resolve(beamPath: BeamPath): Trajectory = {
    val checkpoints = streetSegment.geometry.getCoordinates
    val timeDelta = streetSegment.duration.toDouble / checkpoints.length
    val path = checkpoints.zipWithIndex.map { case (coord, i) =>
      SpaceTime(coord.x, coord.y, (tripStartTime + i * timeDelta).toLong)
    }
    //XXX: let Trajectory logic interpolate intermediate points
    Trajectory(path.toVector)
  }

  override def resolveStart(beamPath: BeamPath): SpaceTime = {
    Option(streetSegment.geometry.getStartPoint).map {
      p => SpaceTime(p.getX, p.getY, tripStartTime)
    }.getOrElse {
      SpaceTime.zero
    }
  }

  override def resolveEnd(beamPath: BeamPath): SpaceTime = {
    Option(streetSegment.geometry.getEndPoint).map {
      p => SpaceTime(p.getX, p.getY, tripStartTime + streetSegment.duration)
    }.getOrElse {
      SpaceTime.zero
    }
  }

  override def resolveLengthInM(beamPath: BeamPath): Double = streetSegment.distance.toDouble / 1000
}

case class TrajectoryByEdgeIdsResolver(@transient streetLayer: StreetLayer, departure: Long, duration: Long) extends TrajectoryResolver {

  override def resolveLengthInM(beamPath: BeamPath): Double = {
    beamPath.linkIds.filter(!_.equals("")).map(_.toInt).map(edgeId =>
      streetLayer.edgeStore.getCursor(edgeId).getLengthM).sum
  }

  override def resolve(beamPath: BeamPath): Trajectory = {
    val stepDelta = duration.toDouble / beamPath.linkIds.size
    val path = beamPath.linkIds.filter(!_.equals("")).map(_.toInt).zipWithIndex.flatMap { case (edgeId, i) =>
      val edge = streetLayer.edgeStore.getCursor(edgeId)
      //TODO: resolve time from stopinfo and tripSchedule(for transit)
      val time = departure + (i * stepDelta).toLong
      edge.getGeometry.getCoordinates.map(coord => SpaceTime(coord.x, coord.y, time))
    }
    Trajectory(path)
  }

  override def resolveStart(beamPath: BeamPath): SpaceTime = {
    beamPath.linkIds.find(!_.equals("")).flatMap { startEdgeId =>
      val edge = streetLayer.edgeStore.getCursor(startEdgeId.toInt)
      Option(edge.getGeometry.getStartPoint).map(p =>
        SpaceTime(p.getX, p.getY, departure)
      )
    }.getOrElse {
      SpaceTime.zero
    }
  }

  override def resolveEnd(beamPath: BeamPath): SpaceTime = {
    beamPath.linkIds.filter(!_.equals("")).lastOption.flatMap { startEdgeId =>
      val edge = streetLayer.edgeStore.getCursor(startEdgeId.toInt)
      Option(edge.getGeometry.getEndPoint).map(p =>
        SpaceTime(p.getX, p.getY, departure + duration)
      )
    }.getOrElse {
      SpaceTime.zero
    }
  }
}

case class DefinedTrajectoryHolder(trajectory: Trajectory) extends TrajectoryResolver {
  override def resolve(beamPath: BeamPath): Trajectory = {
    require(beamPath.resolver == this, "Wrong beam path")
    trajectory
  }

  override def resolveStart(beamPath: BeamPath): SpaceTime = {
    trajectory.path.headOption.getOrElse{
      SpaceTime.zero
    }
  }

  override def resolveEnd(beamPath: BeamPath): SpaceTime = {
    trajectory.path.lastOption.getOrElse{
      SpaceTime.zero
    }
  }

  override def resolveLengthInM(beamPath: BeamPath) = 0.0
}
