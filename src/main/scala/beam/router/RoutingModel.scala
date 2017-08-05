package beam.router

import beam.agentsim.events.SpaceTime
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{ALIGHTING, BOARDING, CAR, TRANSIT, WAITING, WALK}
import beam.router.RoutingModel.BeamStreetPath.empty
import beam.sim.config.BeamConfig
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.vehicles.Vehicle
import org.opentripplanner.routing.vertextype.TransitStop

/**
  * BEAM
  */
object RoutingModel {
  case class BeamTrip(legs: Vector[BeamLeg],
                      choiceUtility: Double = 0.0) {
    lazy val tripClassifier: BeamMode = if (legs map (_.mode) contains CAR) {
      CAR
    } else {
      TRANSIT
    }
    val totalTravelTime: Long = legs.map(_.duration).sum
  }

  object BeamTrip {
    val noneTrip: BeamTrip = BeamTrip(Vector[BeamLeg]())
  }

  case class BeamLeg(startTime: Long,
                     mode: BeamMode,
                     duration: Long,
                     travelPath: Either[BeamStreetPath, BeamTransitSegment] = Left(empty))

  object BeamLeg {
    def dummyWalk(startTime: Long): BeamLeg = new BeamLeg(startTime, WALK, 0)
    def boarding(startTime: Long, duration: Long): BeamLeg = new BeamLeg(startTime, BOARDING, duration)
    def alighting(startTime: Long, duration: Long): BeamLeg = new BeamLeg(startTime, ALIGHTING, duration)
    def waiting(startTime: Long, duration: Long): BeamLeg = new BeamLeg(startTime, WAITING, duration)
    def apply(time: Long, mode: BeamMode, duration: Long, streetPath: BeamStreetPath): BeamLeg =
      BeamLeg(time, mode, duration, Left(streetPath))
    def apply(time: Long, mode: BeamMode, duration: Long, transitSegment: BeamTransitSegment): BeamLeg =
      BeamLeg(time, mode, duration, Right(transitSegment))
  }

  case class BeamTransitSegment(beamVehicleId: Id[Vehicle],
                                fromStopId: Id[TransitStop],
                                toStopId: Id[TransitStop],
                                departureTime: Long)

  case class BeamStreetPath(linkIds: Vector[String],
                            beamVehicleAssignment: Option[Id[BeamVehicleAssignment]] = None,
                            trajectory: Option[Vector[SpaceTime]] = None) {
    def entryTimes = trajectory.getOrElse(Vector()).map(_.time)
    def latLons = trajectory.getOrElse(Vector()).map(_.loc)
    def size  = trajectory.size
  }

  object BeamStreetPath {
    val empty: BeamStreetPath = new BeamStreetPath(Vector[String]())
  }

  case class BeamVehicleAssignment(beamVehicleId: Id[Vehicle], asDriver: Boolean)

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

