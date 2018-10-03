package beam.agentsim.agents.vehicles

import beam.agentsim.agents.vehicles.Trajectory._
import beam.agentsim.events.SpaceTime
import beam.sim.config.BeamConfig
import com.google.inject.Inject
import org.apache.commons.math3.analysis.interpolation.LinearInterpolator
import org.matsim.core.utils.geometry.CoordinateTransformation
import org.matsim.core.utils.geometry.geotools.MGC
import org.matsim.core.utils.geometry.transformations.TransformationFactory

import scala.collection.Searching.{Found, InsertionPoint, _}

object Trajectory {

  lazy val transformer: CoordinateTransformation = TransformationFactory
    .getCoordinateTransformation(TransformationFactory.WGS84, defaultCoordinateSystem)

  @Inject
  var beamConfig: BeamConfig = _

  def defaultCoordinateSystem: String = beamConfig.beam.spatial.localCRS

  def apply(path: Vector[SpaceTime]): Trajectory = {
    new Trajectory(path)
  }

}

/**
  * Describe trajectory as vector of coordinates with time for each coordinate
  */
class Trajectory(val path: Vector[SpaceTime]) {

  private var _path: Vector[SpaceTime] = path

  def coordinateSystem: String = defaultCoordinateSystem

  def location(time: Int): SpaceTime = {
    require(_path.nonEmpty)
    _path.search[SpaceTime](SpaceTime(0, 0, time))(SpaceTime.orderingByTime) match {
      case found: Found =>
        SpaceTime(_path(found.foundIndex).loc, time)
      case InsertionPoint(closestIndex) =>
        //closestPosition = [0, array.len ]
        //TODO: consider some cache for interpolated coords because it's heavy process
        interpolateLocation(time, closestIndex)
    }
  }

  private def interpolateLocation(time: Int, closestPosition: Int) = {
    if (closestPosition > -1 && closestPosition < _path.size) {
      val (prev, next) =
        (Math.max(0, closestPosition - 1), Math.min(closestPosition + 1, _path.length))
      val trajectorySegment = _path.slice(prev, next).toArray
      val timeFunction = _path.slice(prev, next).map(_.time.toDouble).toArray
      val xFunc = trajectorySegment.map(_.loc.getX)
      val yFunc = trajectorySegment.map(_.loc.getY)
      val xInterpolator = new LinearInterpolator()
        .interpolate(timeFunction, xFunc)
      val yInterpolator = new LinearInterpolator()
        .interpolate(timeFunction, yFunc)
      SpaceTime(xInterpolator.value(time), yInterpolator.value(time), time)
    } else if (closestPosition == _path.size) {
      SpaceTime(_path.last.loc, time)
    } else if (_path.nonEmpty) {
      SpaceTime(_path.head.loc, time)
    } else {
      SpaceTime(_path.head.loc, time)
    }
  }

  def computePath(time: Int): Double = {
    val searchFor = SpaceTime(0, 0, time)
    val currentPath = _path.search[SpaceTime](searchFor)(SpaceTime.orderingByTime) match {
      case Found(foundIndex) =>
        val spaceTimes = _path.slice(0, Math.min(foundIndex + 1, _path.length))
        spaceTimes
      case InsertionPoint(insertionPoint) =>
        val knownPath = _path.slice(0, Math.max(0, Math.min(insertionPoint, _path.size)))
        val interpolatedTail = interpolateLocation(time, closestPosition = insertionPoint)
        knownPath :+ interpolatedTail
    }
    currentPath
      .sliding(2)
      .map(pair => {
        val head = MGC.coord2Point(pair.head.loc)
        val last = MGC.coord2Point(pair.last.loc)
        val distance = head.distance(last)
        distance
      })
      .sum
  }

  protected[agentsim] def append(newTrajectory: Trajectory): Unit = {
    _path ++= newTrajectory._path
  }
}
