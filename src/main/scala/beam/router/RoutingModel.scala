package beam.router

import beam.agentsim.agents.vehicles.{PassengerSchedule, Trajectory}
import beam.agentsim.events.SpaceTime
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{CAR, TRANSIT, WALK}
import beam.router.RoutingModel.BeamStreetPath.empty
import beam.sim.BeamServices
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
    //TODO this is a prelimnary version of embodyWithStreetVehicle that assumes Person drives a single access vehicle (either CAR or BIKE) that is left behind as soon as a different mode is encountered in the trip, it also doesn't allow for chaining of Legs without exiting the vehilce in between, e.g. WALK->CAR->CAR->WALK
    //TODO this needs unit testing
    def embodyWithStreetVehicles(trip: BeamTrip, accessVehiclesByMode: Map[BeamMode,Id[Vehicle]], egressVehiclesByMode: Map[BeamMode,Id[Vehicle]], services: BeamServices): EmbodiedBeamTrip = {
      if(trip.legs.size==0){
        EmbodiedBeamTrip.empty
      }else {
        var inAccessPhase = true
        val embodiedLegs: Vector[EmbodiedBeamLeg] = for(beamLeg <- trip.legs) yield {
          val currentMode: BeamMode = beamLeg.mode
          val unbecomeDriverAtComplete = Modes.isR5LegMode(currentMode) && (currentMode != WALK || beamLeg == trip.legs(trip.legs.size - 1))
          if(Modes.isR5TransitMode(currentMode)) {
            inAccessPhase = false
            EmbodiedBeamLeg(beamLeg,services.transitVehiclesByBeamLeg.get(beamLeg).get,false,None,0.0,false)
          }else if(inAccessPhase){
            EmbodiedBeamLeg(beamLeg,accessVehiclesByMode.get(currentMode).get,true,None,0.0,unbecomeDriverAtComplete)
          }else{
            EmbodiedBeamLeg(beamLeg,egressVehiclesByMode.get(currentMode).get,true,None,0.0,unbecomeDriverAtComplete)
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
  }

  case class EmbodiedBeamLeg(beamLeg: BeamLeg,
                             beamVehicleId: Id[Vehicle],
                             asDriver: Boolean,
                             passengerSchedule: Option[PassengerSchedule],
                             cost: BigDecimal,
                             unbecomeDriverOnCompletion: Boolean
                            ) {
    def isHumanBodyVehicle: Boolean = beamVehicleId.toString.equalsIgnoreCase("body")
  }
  object EmbodiedBeamLeg {
    def apply(leg: BeamLeg): EmbodiedBeamLeg = EmbodiedBeamLeg(leg, Id.create("",classOf[Vehicle]),false,None,0.0,false)
  }

  sealed abstract class BeamPath {
    def toTrajectory: Trajectory = Trajectory(this)
    def isStreet: Boolean = false
    def isTransit: Boolean = false
  }

  case class BeamTransitSegment(fromStopId: String,
                                toStopId: String,
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

