package beam.router

import Modes.BeamMode
import Modes.BeamMode.{CAR, TRANSIT, WALK}
import beam.agentsim.events.SpaceTime
import org.matsim.api.core.v01.Coord

/**
  * BEAM
  */
object RoutingModel {
  case class BeamTrip(legs: Vector[BeamLeg], choiceUtility: Double = 0.0) {
    lazy val tripClassifier: BeamMode = if (legs map (_.mode) contains CAR) {
      CAR
    } else {
      TRANSIT
    }
    val totalTravelTime: Long = legs.map(_.travelTime).sum
  }

  object BeamTrip {
    val noneTrip: BeamTrip = BeamTrip(Vector[BeamLeg]())
  }

  case class BeamLeg(startTime: Long, mode: BeamMode, travelTime: Long, graphPath: BeamGraphPath)

  object BeamLeg {
    def dummyWalk(startTime: Long): BeamLeg = new BeamLeg(startTime, WALK, 0, BeamGraphPath.empty)
  }

  case class BeamGraphPath(linkIds: Vector[String],
                           latLons: Vector[Coord],
                           entryTimes: Vector[Long]) {

    lazy val trajectory: Vector[SpaceTime] = {
      latLons zip entryTimes map {
        SpaceTime(_)
      }
    }

    def size  = latLons.size
  }

  object BeamGraphPath {
    val emptyTimes: Vector[Long] = Vector[Long]()
    val errorPoints: Vector[Coord] = Vector[Coord](new Coord(0.0, 0.0))
    val errorTime: Vector[Long] = Vector[Long](-1L)

    val empty: BeamGraphPath = new BeamGraphPath(Vector[String](), errorPoints, emptyTimes)
  }

  case class EdgeModeTime(fromVertexLabel: String, mode: BeamMode, time: Long, fromCoord: Coord, toCoord: Coord)

  /**
    * Represent the time in seconds since midnight.
    * @param atTime seconds since midnight
    */
  abstract class BeamTime(atTime: Int)
  case class DiscreteTime(atTime: Int) extends BeamTime(atTime)
  case class WindowTime(atTime: Int, timeFrame: Int = 15*60) extends BeamTime(atTime) {
    lazy val fromTime: Int = atTime - (timeFrame/2) -(timeFrame%2)
    lazy val toTime: Int = atTime + (timeFrame/2)
  }

}

