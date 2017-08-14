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
import scala.collection.mutable

/**
  * BEAM
  */
object RoutingModel {

  case class BeamTrip(legs: Vector[BeamLeg],
                      accessMode: BeamMode) {
    lazy val tripClassifier: BeamMode = if (legs map (_.mode) contains CAR) {
      CAR
    } else {
      TRANSIT
    }
    val totalTravelTime: Long = legs.map(_.duration).sum
    def estimateCost(fare: BigDecimal) = Vector(BigDecimal(0.0))
  }

  object BeamTrip {
    def apply(legs: Vector[BeamLeg]): BeamTrip = BeamTrip(legs,legs.head.mode)
    val empty: BeamTrip = BeamTrip(Vector(), BeamMode.WALK)
  }

  case class EmbodiedBeamTrip(legs: Vector[EmbodiedBeamLeg]) {
    lazy val tripClassifier: BeamMode = if (legs map (_.beamLeg.mode) contains CAR) {
      CAR
    } else {
      TRANSIT
    }
    val totalTravelTime: Long = legs.map(_.beamLeg.duration).sum
    def estimateCost(fare: BigDecimal) = Vector(BigDecimal(0.0))
    def beamLegs(): Vector[BeamLeg] = legs.map(embodiedLeg => embodiedLeg.beamLeg)
    def toBeamTrip(): BeamTrip = BeamTrip(beamLegs())
  }
  object EmbodiedBeamTrip {
    //TODO this needs unit testing
    def embodyWithStreetVehicle(trip: BeamTrip, vehId: Id[Vehicle], mode: BeamMode): EmbodiedBeamTrip = {
      if(trip.legs.size==0){
        EmbodiedBeamTrip.empty
      }else {
        var startedInsertingVehicle = false
        var stoppedInsertingVehicle = false
        val embodiedLegs: Vector[EmbodiedBeamLeg] = for(beamLeg <- trip.legs) yield {
          val currentMode = beamLeg.mode
          if(startedInsertingVehicle & stoppedInsertingVehicle){
            EmbodiedBeamLeg(beamLeg,beamModeToVehicleId(beamLeg.mode),false,None,0.0)
          }else{
            if(currentMode == mode){
              startedInsertingVehicle = true
              EmbodiedBeamLeg(beamLeg,vehId,true,None,0.0)
            }else{
              if(startedInsertingVehicle)stoppedInsertingVehicle = true
              EmbodiedBeamLeg(beamLeg,beamModeToVehicleId(beamLeg.mode),false,None,0.0)
            }
          }
        }
        EmbodiedBeamTrip(embodiedLegs)
      }
    }
    def beamModeToVehicleId(beamMode: BeamMode): Id[Vehicle] = {
      if (beamMode == WALK) {
        Id.create("body", classOf[Vehicle])
      } else if (Modes.isR5TransitMode(beamMode)) {
        Id.create("transit", classOf[Vehicle])
      } else {
        Id.create("", classOf[Vehicle])
      }
    }
    val empty: EmbodiedBeamTrip = EmbodiedBeamTrip(BeamTrip.empty)
    def apply(beamTrip: BeamTrip) = new EmbodiedBeamTrip(beamTrip.legs.map(leg => EmbodiedBeamLeg(leg)))
  }

  /**
    *
    * @param startTime time in seconds from base midnight
    * @param mode
    * @param duration period in seconds
    * @param travelPath
    */
  case class BeamLeg(startTime: Long,
                     mode: BeamMode,
                     duration: Long,
                     travelPath: BeamPath = empty) {
    def endTime: Long = startTime + duration
  }

  object BeamLeg {
    val beamLegOrdering: Ordering[BeamLeg] = Ordering.by(_.startTime)
    def dummyWalk(startTime: Long): BeamLeg = new BeamLeg(startTime, WALK, 0)
    def boarding(startTime: Long, duration: Long): BeamLeg = new BeamLeg(startTime, BOARDING, duration)
    def alighting(startTime: Long, duration: Long): BeamLeg = new BeamLeg(startTime, ALIGHTING, duration)
    def waiting(startTime: Long, duration: Long): BeamLeg = new BeamLeg(startTime, WAITING, duration)
  }

  case class EmbodiedBeamLeg(beamLeg: BeamLeg,
                             beamVehicleId: Id[Vehicle],
                             asDriver: Boolean,
                             passengerSchedule: Option[PassengerSchedule],
                             cost: BigDecimal
                            ) {
    def isHumanBodyVehicle: Boolean = beamVehicleId.toString.equalsIgnoreCase("body")
  }
  object EmbodiedBeamLeg {
    def apply(leg: BeamLeg): EmbodiedBeamLeg = EmbodiedBeamLeg(leg, Id.create("",classOf[Vehicle]),false,None,0.0)
  }

  sealed abstract class BeamPath {
    def toTrajectory: Trajectory = Trajectory(this)
    def isStreet: Boolean = false
    def isTransit: Boolean = false
  }

  case class BeamTransitSegment(beamVehicleId: Id[Vehicle],
                                fromStopId: Id[TransitStop],
                                toStopId: Id[TransitStop],
                                departureTime: Long) extends BeamPath {
    override def isTransit = true
  }

  case class BeamStreetPath(linkIds: Vector[String],
                            beamVehicleId: Option[Id[Vehicle]] = None,
                            trajectory: Option[Vector[SpaceTime]] = None) extends BeamPath {

    override def isStreet = true

    def entryTimes = trajectory.getOrElse(Vector()).map(_.time)
    def latLons = trajectory.getOrElse(Vector()).map(_.loc)
    def size  = trajectory.size
  }

  object BeamStreetPath {
    val empty: BeamStreetPath = new BeamStreetPath(Vector[String]())
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
    lazy val fromTime: Int = atTime
    lazy val toTime: Int = atTime + timeFrame
  }

  object WindowTime {
    def apply(atTime: Int, r5: BeamConfig.Beam.Routing.R5): WindowTime =
      new WindowTime(atTime, r5.departureWindow * 60)
  }
}

