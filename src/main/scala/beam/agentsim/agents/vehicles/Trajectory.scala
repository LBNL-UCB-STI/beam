package beam.agentsim.agents.vehicles

import beam.agentsim.agents.vehicles.Trajectory._
import beam.sim.config.ConfigModule._
import beam.agentsim.events.SpaceTime
import beam.router.RoutingModel.{BeamPath, EmptyBeamPath}
import com.conveyal.r5.api.util.TransitSegment
import org.apache.commons.math3.analysis.interpolation.LinearInterpolator
import org.matsim.core.utils.geometry.geotools.MGC
import org.matsim.core.utils.geometry.transformations.TransformationFactory

import scala.collection.Searching.{Found, InsertionPoint, _}

object Trajectory {
  val transformer = TransformationFactory.getCoordinateTransformation(TransformationFactory.WGS84, defaultCoordinateSystem)

  def defaultCoordinateSystem = beamConfig.beam.spatial.localCRS

  def apply(path: Vector[SpaceTime]): Trajectory = {
    new Trajectory(path)
  }

}
/**
  * Describe trajectory as vector of coordinates with time for each coordinate
  */
class Trajectory(val path: Vector[SpaceTime]) {

  private var _path: Vector[SpaceTime] = path

  def coordinateSystem = defaultCoordinateSystem

  protected[agentsim] def append(newTrajectory: Trajectory) = {
    this.synchronized{
      _path = _path ++ newTrajectory._path
    }
  }

  def location(time: Double): SpaceTime = {
    require(_path.nonEmpty)
    val timeL = Math.floor(time).toLong
    _path.search[SpaceTime](SpaceTime(0, 0,timeL))(SpaceTime.orderingByTime) match {
      case found: Found =>
        SpaceTime(_path(found.foundIndex).loc, timeL)
      case InsertionPoint(closestIndex) =>
        //closestPosition = [0, array.len ]
        //TODO: consider some cache for interpolated coords because it's heavy process
        interpolateLocation(time, closestIndex)
    }
  }

  private def interpolateLocation(time: Double, closestPosition: Int) = {
    val timeL = Math.floor(time).toLong
    if (closestPosition > -1 && closestPosition < _path.size) {
      val (prev, next) = (Math.max(0, closestPosition - 1), Math.min(closestPosition + 1, _path.length))
      val trajectorySegment = _path.slice(prev, next).toArray
      val timeFunction = _path.slice(prev, next).map(_.time.toDouble).toArray
      val xFunc = trajectorySegment.map(_.loc.getX)
      val yFunc = trajectorySegment.map(_.loc.getY)
      val xInterpolator = new LinearInterpolator()
        .interpolate(timeFunction, xFunc)
      val yInterpolator = new LinearInterpolator()
        .interpolate(timeFunction, yFunc)
      SpaceTime(xInterpolator.value(time), yInterpolator.value(time), timeL)
    } else if (closestPosition == _path.size) {
      SpaceTime(_path.last.loc, timeL)
    } else if (_path.nonEmpty){
      SpaceTime(_path.head.loc, timeL)
    } else {
      SpaceTime(_path.head.loc, timeL)
    }
  }

  def computePath(time: Double) = {
    val searchFor = SpaceTime(0, 0, Math.floor(time).toLong)
    val currentPath = _path.search[SpaceTime](searchFor)(SpaceTime.orderingByTime) match {
      case Found(foundIndex) =>
        val spaceTimes = _path.slice(0, Math.min(foundIndex + 1, _path.length))
        spaceTimes
      case InsertionPoint(insertionPoint) =>
        val knownPath = _path.slice(0, Math.max(0, Math.min(insertionPoint, _path.size)))
        val interpolatedTail = interpolateLocation(time, closestPosition = insertionPoint)
        knownPath :+ interpolatedTail
    }
    currentPath.sliding(2).map(pair => {
      val head = MGC.coord2Point(pair.head.loc)
      val last = MGC.coord2Point(pair.last.loc)
      val distance = head.distance(last)
      distance
    }).sum
  }
}