package beam.router

import beam.agentsim.agents.vehicles.BeamVehicle.StreetVehicle
import beam.agentsim.agents.vehicles.{HumanBodyVehicle, PassengerSchedule, Trajectory}
import beam.agentsim.events.SpaceTime
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{BIKE, CAR, RIDEHAIL, TRANSIT, WALK}
import beam.sim.BeamServices
import beam.sim.config.BeamConfig
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.vehicles.Vehicle

/**
  * BEAM
  */
object RoutingModel {

  type LegCostEstimator = BeamLeg => Option[Double]

  case class BeamTrip(legs: Vector[BeamLeg],
                      accessMode: BeamMode) {
    lazy val tripClassifier: BeamMode = if (legs map (_.mode) contains CAR) {
      CAR
    } else {
      TRANSIT
    }
    val totalTravelTime: Long = legs.map(_.duration).sum
  }

  object BeamTrip {
    def apply(legs: Vector[BeamLeg]): BeamTrip = BeamTrip(legs, legs.head.mode)

    val empty: BeamTrip = BeamTrip(Vector(), BeamMode.WALK)
  }

  case class EmbodiedBeamTrip(legs: Vector[EmbodiedBeamLeg]) {

    lazy val costEstimate: BigDecimal = legs.map(_.cost).sum /// Generalize or remove
    lazy val tripClassifier: BeamMode = determineTripMode(legs)
    lazy val vehiclesInTrip: Vector[Id[Vehicle]] = determineVehiclesInTrip(legs)
    val totalTravelTime: Long = legs.map(_.beamLeg.duration).sum

    def beamLegs(): Vector[BeamLeg] = legs.map(embodiedLeg => embodiedLeg.beamLeg)

    def toBeamTrip(): BeamTrip = BeamTrip(beamLegs())

    def determineTripMode(legs: Vector[EmbodiedBeamLeg]): BeamMode = {
      var theMode: BeamMode = WALK
      legs.foreach { leg =>
        // Any presence of transit makes it transit
        if (leg.beamLeg.mode.isTransit) {
          theMode = TRANSIT
        } else if (theMode == WALK && leg.beamLeg.mode == CAR) {
          if((legs.size == 1 && legs(0).beamVehicleId.toString.contains("rideHailingVehicle")) ||
            (legs.size>1 && legs(1).beamVehicleId.toString.contains("rideHailingVehicle")) ){
            theMode = RIDEHAIL
          }else{
            theMode = CAR
          }
        } else if (theMode == WALK && leg.beamLeg.mode == BIKE) {
          theMode = BIKE
        }
      }
      theMode
    }

    def determineVehiclesInTrip(legs: Vector[EmbodiedBeamLeg]): Vector[Id[Vehicle]] = {
      legs.map(leg => leg.beamVehicleId).distinct
    }
    override def toString() = {
      s"EmbodiedBeamTrip(${tripClassifier} starts ${legs.head.beamLeg.startTime} legModes ${legs.map(_.beamLeg.mode).mkString(",")})"
    }
  }

  object EmbodiedBeamTrip {


    //TODO this is a prelimnary version of embodyWithStreetVehicle that assumes Person drives a single access vehicle (either CAR or BIKE) that is left behind as soon as a different mode is encountered in the trip, it also doesn't allow for chaining of Legs without exiting the vehilce in between, e.g. WALK->CAR->CAR->WALK
    //TODO this needs unit testing
    def embodyWithStreetVehicles(trip: BeamTrip, accessVehiclesByMode: Map[BeamMode, StreetVehicle], egressVehiclesByMode: Map[BeamMode, StreetVehicle], legFares: Map[Int, Double], services: BeamServices): EmbodiedBeamTrip = {
      if(trip.legs.isEmpty){
        EmbodiedBeamTrip.empty
      } else {
        var inAccessPhase = true
        val embodiedLegs: Vector[EmbodiedBeamLeg] = for(tuple <- trip.legs.zipWithIndex) yield {
          val beamLeg = tuple._1
          val currentMode: BeamMode = beamLeg.mode
          val unbecomeDriverAtComplete = Modes.isR5LegMode(currentMode) && (currentMode != WALK || beamLeg == trip.legs(trip.legs.size - 1))

          val cost = legFares.getOrElse(tuple._2, 0.0)
          if (Modes.isR5TransitMode(currentMode)) {
            if(services.transitVehiclesByBeamLeg.contains(beamLeg)) {
              EmbodiedBeamLeg(beamLeg, services.transitVehiclesByBeamLeg(beamLeg), false, None, 0.0, false)
            }else{
              EmbodiedBeamLeg.empty
            }
          } else if (inAccessPhase) {
            EmbodiedBeamLeg(beamLeg, accessVehiclesByMode(currentMode).id, accessVehiclesByMode(currentMode).asDriver, None, 0.0, unbecomeDriverAtComplete)
          } else {
            EmbodiedBeamLeg(beamLeg, egressVehiclesByMode(currentMode).id, egressVehiclesByMode(currentMode).asDriver, None, 0.0, unbecomeDriverAtComplete)
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

    val empty: EmbodiedBeamTrip = EmbodiedBeamTrip(BeamTrip.empty.legs.map(leg=>EmbodiedBeamLeg(leg)))
  }

  /**
    *
    * @param startTime time in seconds from base midnight
    * @param mode
    * @param duration  period in seconds
    * @param travelPath
    */
  case class BeamLeg(startTime: Long,
                     mode: BeamMode,
                     duration: Long,
                     travelPath: BeamPath = EmptyBeamPath.path) {
    val endTime: Long = startTime + duration

    override def toString: String = s"BeamLeg(${mode} @ ${startTime},dur:${duration},path: ${travelPath.toShortString})"
  }

  object BeamLeg {
    val beamLegOrdering: Ordering[BeamLeg] = Ordering.by(x=>(x.startTime,x.duration))

    def dummyWalk(startTime: Long): BeamLeg = new BeamLeg(startTime, WALK, 0)
  }

  case class BeamLegWithNext(leg: BeamLeg, nextLeg: Option[BeamLeg])

  case class EmbodiedBeamLeg(beamLeg: BeamLeg,
                             beamVehicleId: Id[Vehicle],
                             asDriver: Boolean,
                             passengerSchedule: Option[PassengerSchedule],
                             cost: BigDecimal,
                             unbecomeDriverOnCompletion: Boolean
                            ) {
    val isHumanBodyVehicle: Boolean = HumanBodyVehicle.isHumanBodyVehicle(beamVehicleId)
  }

  object EmbodiedBeamLeg {
    def apply(leg: BeamLeg): EmbodiedBeamLeg = EmbodiedBeamLeg(leg, Id.create("", classOf[Vehicle]), false, None, 0.0, false)

    def empty: EmbodiedBeamLeg = EmbodiedBeamLeg(BeamLeg.dummyWalk(0L), Id.create("", classOf[Vehicle]), false, None, 0.0, false)
  }

  case class TransitStopsInfo(fromStopId: Int, toStopId: Int)

  /**
    *
    * @param linkIds either matsim linkId or R5 edgeIds that describes whole path
    * @param transitStops start and end stop if this path is transit (partial) route

    */
  case class BeamPath(linkIds: Vector[String], transitStops: Option[TransitStopsInfo], protected[router] val resolver: TrajectoryResolver) {

    def isTransit = transitStops.isDefined

    def toTrajectory: Trajectory = {
      resolver.resolve(this)
    }

    lazy val distanceInM: Double = resolver.resolveLengthInM(this)

    def toShortString() = if(linkIds.size >0){ s"${linkIds.head} .. ${linkIds(linkIds.size - 1)}"}else{""}

    def getStartPoint() = resolver.resolveStart(this)

    def getEndPoint() = resolver.resolveEnd(this)

    def canEqual(other: Any): Boolean = other.isInstanceOf[BeamPath]

    override def equals(other: Any): Boolean = other match {
      case that: BeamPath =>
        (that eq this) || (
            if (this.isTransit && that.isTransit) {
              transitStops == that.transitStops
            } else if (!this.isTransit && !that.isTransit) {
              this.linkIds == that.linkIds
            } else {
              false
            }
          )
      case _ => false
    }

    override def hashCode(): Int = {
      if (this.isTransit) {
        transitStops.hashCode()
      } else {
        linkIds.hashCode()
      }
    }
  }

  //case object EmptyBeamPath extends BeamPath(Vector[String](), None, departure = SpaceTime(Double.PositiveInfinity, Double.PositiveInfinity, Long.MaxValue), arrival = SpaceTime(Double.NegativeInfinity, Double.NegativeInfinity, Long.MinValue))
  object EmptyBeamPath {
    val path = BeamPath(Vector[String](), None, EmptyTrajectoryResolver)
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
    def apply(atTime: Int, departureWindow: Double): WindowTime =
      new WindowTime(atTime, math.round(departureWindow * 60.0).toInt)
  }

}

