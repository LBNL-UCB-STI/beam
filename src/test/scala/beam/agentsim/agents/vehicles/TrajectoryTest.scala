package beam.agentsim.agents.vehicles

import beam.agentsim.events.SpaceTime
import org.matsim.api.core.v01.Coord
import org.scalatest.{FlatSpec, Matchers}

/**
  * @author dserdiuk
  */
class TrajectoryTest extends FlatSpec with Matchers {


  behavior of "Trajectory"

  it should "interpolate coordinates" in {
    val route = Vector(SpaceTime(0.0,0.0,0), SpaceTime(4.0,4.0,4))
    val trajectory = new Trajectory(route)
    val start = trajectory.location(0.0)
    val end = trajectory.location(4.0)
    val middle = trajectory.location(2.0)
    val otherPoint = trajectory.location(2.5)

    start.loc should === (new Coord(0.0,0.0))
    end.loc should === (new Coord(4.0,4.0))
    middle.loc should === (new Coord(2.0,2.0))
    otherPoint.loc should === (new Coord(2.5,2.5))

    val path = trajectory.computePath(2.0)
    //XXX: trajectory operation in Cartesian coord system
    path should === (Math.sqrt(8.0))
  }

}
