package beam.router

import beam.agentsim.agents.vehicles.{PassengerSchedule, Trajectory}
import beam.agentsim.events.SpaceTime
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{ALIGHTING, BOARDING, CAR, TRANSIT, WAITING, WALK}
import beam.router.RoutingModel.BeamStreetPath.empty
import beam.sim.config.BeamConfig
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.vehicles.Vehicle
import org.opentripplanner.routing.vertextype.TransitStop

import scala.collection.immutable.TreeMap

/**
  * BEAM
  */
object RoutingModel {
  case class BeamTrip(legs: TreeMap[BeamLeg,BeamVehicleAssignment],
                      choiceUtility: Double = 0.0) {
    lazy val tripClassifier: BeamMode = if (legs.keys.toVector map (_.mode) contains CAR) {
      CAR
    } else {
      TRANSIT
    }
    val totalTravelTime: Long = legs.keys.map(_.duration).sum
    def estimateCost(fare: BigDecimal) = Vector(BigDecimal(0.0))
  }

  object BeamTrip {
    def apply(legsAsVector: Vector[BeamLeg]): BeamTrip = {
      var legMap = TreeMap[BeamLeg,BeamVehicleAssignment]()(BeamLeg.beamLegOrdering)
      legsAsVector.foreach(leg => legMap += (leg -> BeamVehicleAssignment.empty))
      BeamTrip(legMap)
    }
    val empty: BeamTrip = BeamTrip(TreeMap[BeamLeg,BeamVehicleAssignment]()(BeamLeg.beamLegOrdering))
  }

  case class BeamLeg(startTime: Long,
                     mode: BeamMode,
                     duration: Long,
                     travelPath: BeamPath = empty){
    def endTime: Long = startTime + duration
  }

  object BeamLeg {
    val beamLegOrdering: Ordering[BeamLeg] = Ordering.by(_.startTime)
    def dummyWalk(startTime: Long): BeamLeg = new BeamLeg(startTime, WALK, 0)
    def boarding(startTime: Long, duration: Long): BeamLeg = new BeamLeg(startTime, BOARDING, duration)
    def alighting(startTime: Long, duration: Long): BeamLeg = new BeamLeg(startTime, ALIGHTING, duration)
    def waiting(startTime: Long, duration: Long): BeamLeg = new BeamLeg(startTime, WAITING, duration)
//    def apply(time: Long, mode: BeamMode, duration: Long, streetPath: BeamStreetPath): BeamLeg =
//      BeamLeg(time, mode, duration, Left(streetPath))
//    def apply(time: Long, mode: BeamMode, duration: Long, transitSegment: BeamTransitSegment): BeamLeg =
//      BeamLeg(time, mode, duration, Right(transitSegment))
  }

  sealed abstract class BeamPath {
    def toTrajectory: Trajectory = ???
    def isStreet: Boolean = false
    def isTransit: Boolean = false
  }

  case class BeamTransitSegment(beamVehicleId: Id[Vehicle],
                                fromStopId: Id[TransitStop],
                                toStopId: Id[TransitStop],
                                departureTime: Long) extends BeamPath {
    override def isTransit = true
    override def toTrajectory = Trajectory(this)
  }

  case class BeamStreetPath(linkIds: Vector[String],
                            beamVehicleId: Option[Id[Vehicle]] = None,
                            trajectory: Option[Vector[SpaceTime]] = None) extends BeamPath {

    override def isStreet = true
    override def toTrajectory = new Trajectory(this)

    def entryTimes = trajectory.getOrElse(Vector()).map(_.time)
    def latLons = trajectory.getOrElse(Vector()).map(_.loc)
    def size  = trajectory.size
  }

  object BeamStreetPath {
    val empty: BeamStreetPath = new BeamStreetPath(Vector[String]())
  }

  case class BeamVehicleAssignment(beamVehicleId: Id[Vehicle], asDriver: Boolean, passengerSchedule: Option[PassengerSchedule])
  object BeamVehicleAssignment{
    val empty: BeamVehicleAssignment = BeamVehicleAssignment(Id.create("Empty",classOf[Vehicle]),false,None)
  }

  case class EdgeModeTime(fromVertexLabel: String, mode: BeamMode, time: Long, fromCoord: Coord, toCoord: Coord)

  /**
    * Represent the time in seconds since midnight.
    * attribute atTime seconds since midnight
    */
  sealed trait BeamTime {
    val atTime: Int
  }
  case class DiscreteTime(override val atTime: Int) extends BeamTime
  case class WindowTime(override val atTime: Int, timeFrame: Int = 15 * 60) extends BeamTime {
    lazy val fromTime: Int = atTime - (timeFrame/2) -(timeFrame%2)
    lazy val toTime: Int = atTime + (timeFrame/2)
  }
  object WindowTime {
    def apply(atTime: Int, r5: BeamConfig.Beam.Routing.R5): WindowTime =
      new WindowTime(atTime, r5.departureWindow * 60)
  }
}

