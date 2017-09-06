package beam.router

import beam.agentsim.agents.vehicles.BeamVehicle.StreetVehicle
import beam.agentsim.agents.vehicles.{PassengerSchedule, Trajectory}
import beam.agentsim.events.SpaceTime
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{BIKE, CAR, RIDEHAIL, TRANSIT, WALK}
import beam.router.RoutingModel.BeamStreetPath.empty
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
            inAccessPhase = false
            EmbodiedBeamLeg(beamLeg,services.transitVehiclesByBeamLeg.get(beamLeg).get,false,None,cost,false)
          } else if (inAccessPhase) {
            EmbodiedBeamLeg(beamLeg, accessVehiclesByMode.get(currentMode).get.id, accessVehiclesByMode.get(currentMode).get.asDriver, None, cost, unbecomeDriverAtComplete)
          } else {
            EmbodiedBeamLeg(beamLeg, egressVehiclesByMode.get(currentMode).get.id, egressVehiclesByMode.get(currentMode).get.asDriver, None, cost, unbecomeDriverAtComplete)
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
    * @param duration  period in seconds
    * @param travelPath
    */
  case class BeamLeg(startTime: Long,
                     mode: BeamMode,
                     duration: Long,
                     travelPath: BeamPath = empty) {
    def endTime: Long = startTime + duration
  }

  object BeamLeg {
    val beamLegOrdering: Ordering[BeamLeg] = Ordering.by(x=>(x.startTime,x.duration))

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
    def apply(leg: BeamLeg): EmbodiedBeamLeg = EmbodiedBeamLeg(leg, Id.create("", classOf[Vehicle]), false, None, 0.0, false)

    def empty: EmbodiedBeamLeg = EmbodiedBeamLeg(BeamLeg.dummyWalk(0L), Id.create("", classOf[Vehicle]), false, None, 0.0, false)
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

    def size = trajectory.size
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

