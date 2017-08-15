package beam.agentsim.agents.vehicles

import beam.agentsim.agents.vehicles.Trajectory._
import beam.sim.config.ConfigModule._
import beam.agentsim.events.SpaceTime
import beam.router.RoutingModel.{BeamPath, BeamStreetPath, BeamTransitSegment}
import com.conveyal.r5.api.util.TransitSegment
import org.apache.commons.math3.analysis.interpolation.LinearInterpolator
import org.matsim.core.utils.geometry.geotools.MGC
import org.matsim.core.utils.geometry.transformations.TransformationFactory

import scala.collection.Searching.{Found, InsertionPoint, _}

object Trajectory {
  val transformer = TransformationFactory.getCoordinateTransformation(TransformationFactory.WGS84, defaultCoordinateSystem)

  def defaultCoordinateSystem = beamConfig.beam.routing.gtfs.crs

  def apply(path: BeamPath): Trajectory = {
    new Trajectory(path)
  }

}
/**
  * Describe trajectory as vector of coordinates with time for each coordinate
  * @param streetPath
  */
class Trajectory(streetPath: BeamPath) {

  def this()= {
    this(BeamStreetPath.empty.copy())
  }
  private var _path: BeamStreetPath = streetPath match {
    case path: BeamStreetPath => path
    //TODO this is a stub but needs to include a transformation from TransitSegment to StreetPath (with empty linkId Vector)
    case _ => BeamStreetPath.empty
  }

  def coordinateSystem = defaultCoordinateSystem

  protected[agentsim] def append(newTrajectory: Trajectory) = {
    val spacetimes = newTrajectory._path.entryTimes.zip(newTrajectory._path.latLons).map(tup => SpaceTime(tup._2.getX, tup._2.getY, tup._1))
    newTrajectory._path.linkIds.zip(spacetimes).foreach(stringCoord => this.appendOne(stringCoord))
  }
  protected[agentsim] def appendOne(coords: (String,SpaceTime)) = {
    //val transformed = transformer.transform(coords._2.loc)
    val transformed = coords._2
    // x -> longitude, y -> latitude
    this._path = this._path.copy(linkIds = this._path.linkIds :+ coords._1, trajectory = Option(this._path.trajectory.getOrElse(Vector()) :+ transformed))
  }

  def location(time: Double): SpaceTime = {
    require(_path.size > 0)
    require(_path.latLons.length == _path.entryTimes.length)
    val timeL = Math.floor(time).toLong
    _path.entryTimes.search(timeL) match {
      case found: Found =>
        SpaceTime(_path.latLons(found.foundIndex), timeL)
      case InsertionPoint(closestIndex) =>
        //closestPosition = [0, array.len ]
        //TODO: consider some cache for interpolated coords because it's heavy process
        interpolateLocation(time, closestIndex)
    }
  }

  private def interpolateLocation(time: Double, closestPosition: Int) = {
    val timeL = Math.floor(time).toLong
    if (closestPosition > -1 && closestPosition < _path.size) {
      val (prev, next) = (Math.max(0, closestPosition - 1), Math.min(closestPosition + 1, _path.entryTimes.length))
      val trajectorySegment = _path.latLons.slice(prev, next).toArray
      val timeFunction = _path.entryTimes.slice(prev, next).map(_.toDouble).toArray
      val xFunc = trajectorySegment.map(_.getX)
      val yFunc = trajectorySegment.map(_.getY)
      val xInterpolator = new LinearInterpolator()
        .interpolate(timeFunction, xFunc)
      val yInterpolator = new LinearInterpolator()
        .interpolate(timeFunction, yFunc)
      SpaceTime(xInterpolator.value(time), yInterpolator.value(time), timeL)
    } else if (closestPosition == _path.size) {
      SpaceTime(_path.latLons.last, timeL)
    } else if (_path.latLons.nonEmpty){
      SpaceTime(_path.latLons.head, timeL)
    } else {
      SpaceTime(_path.latLons.head, timeL)
    }
  }

  def computePath(time: Double) = {
    val currentPath = _path.entryTimes.search(Math.floor(time).toLong) match {
      case Found(foundIndex) =>
        _path.latLons.slice(0, Math.min(foundIndex + 1, _path.size))
      case InsertionPoint(insertionPoint) =>
        val knownPath = _path.latLons.slice(0, Math.max(0, Math.min(insertionPoint, _path.size)))
        val interpolatedTail = interpolateLocation(time, closestPosition = insertionPoint).loc
        knownPath :+ interpolatedTail
    }
    currentPath.sliding(2).map(pair => {
      val head = MGC.coord2Point(pair.head)
      val last = MGC.coord2Point(pair.last)
      val distance = head.distance(last)
      distance
    }).sum
  }
}