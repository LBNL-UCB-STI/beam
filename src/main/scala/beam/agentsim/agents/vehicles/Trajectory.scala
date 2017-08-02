package beam.agentsim.agents.vehicles

import beam.agentsim.agents.vehicles.Trajectory._
import beam.sim.config.ConfigModule._
import beam.agentsim.events.SpaceTime
import beam.router.RoutingModel.BeamStreetPath
import org.apache.commons.math3.analysis.interpolation.LinearInterpolator
import org.matsim.core.utils.geometry.geotools.MGC
import org.matsim.core.utils.geometry.transformations.TransformationFactory

import scala.collection.Searching.{Found, InsertionPoint, _}

object Trajectory {
  val transformer = TransformationFactory.getCoordinateTransformation(TransformationFactory.WGS84, defaultCoordinateSystem)

  def defaultCoordinateSystem = beamConfig.beam.routing.gtfs.crs

}
/**
  * Describe trajectory as vector of coordinates with time for each coordinate
  * @param initialRoute
  */
class Trajectory(initialRoute: BeamStreetPath) {

  def this()= {
    this(BeamStreetPath.empty.copy())
  }
  private var _route = initialRoute

  def coordinateSystem = defaultCoordinateSystem

  protected[agentsim] def append(coords: (String,SpaceTime)) = {
    //val transformed = transformer.transform(coords._2.loc)
    val transformed = coords._2
    // x -> longitude, y -> latitude
    this._route = this._route.copy(linkIds = this._route.linkIds :+ coords._1, trajectory = Option(this._route.trajectory.getOrElse(Vector()) :+ transformed))
  }

  def location(time: Double): SpaceTime = {
    require(_route.size > 0)
    require(_route.latLons.length == _route.entryTimes.length)
    val timeL = Math.floor(time).toLong
    _route.entryTimes.search(timeL) match {
      case found: Found =>
        SpaceTime(_route.latLons(found.foundIndex), timeL)
      case InsertionPoint(closestIndex) =>
        //closestPosition = [0, array.len ]
        //TODO: consider some cache for interpolated coords because it's heavy process
        interpolateLocation(time, closestIndex)
    }
  }

  private def interpolateLocation(time: Double, closestPosition: Int) = {
    val timeL = Math.floor(time).toLong
    if (closestPosition > -1 && closestPosition < _route.size) {
      val (prev, next) = (Math.max(0, closestPosition - 1), Math.min(closestPosition + 1, _route.entryTimes.length))
      val trajectorySegment = _route.latLons.slice(prev, next).toArray
      val timeFunction = _route.entryTimes.slice(prev, next).map(_.toDouble).toArray
      val xFunc = trajectorySegment.map(_.getX)
      val yFunc = trajectorySegment.map(_.getY)
      val xInterpolator = new LinearInterpolator()
        .interpolate(timeFunction, xFunc)
      val yInterpolator = new LinearInterpolator()
        .interpolate(timeFunction, yFunc)
      SpaceTime(xInterpolator.value(time), yInterpolator.value(time), timeL)
    } else if (closestPosition == _route.size) {
      SpaceTime(_route.latLons.last, timeL)
    } else if (_route.latLons.nonEmpty){
      SpaceTime(_route.latLons.head, timeL)
    } else {
      SpaceTime(_route.latLons.head, timeL)
    }
  }

  def computePath(time: Double) = {
    val currentPath = _route.entryTimes.search(Math.floor(time).toLong) match {
      case Found(foundIndex) =>
        _route.latLons.slice(0, Math.min(foundIndex + 1, _route.size))
      case InsertionPoint(insertionPoint) =>
        val knownPath = _route.latLons.slice(0, Math.max(0, Math.min(insertionPoint, _route.size)))
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