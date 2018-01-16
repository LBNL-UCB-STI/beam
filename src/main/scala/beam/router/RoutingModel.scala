package beam.router

import beam.agentsim.agents.vehicles.BeamVehicleType.HumanBodyVehicle
import beam.agentsim.agents.vehicles.PassengerSchedule
import beam.agentsim.events.SpaceTime
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{BIKE, CAR, DRIVE_TRANSIT, RIDEHAIL, TRANSIT, WALK, WALK_TRANSIT}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.vehicles.Vehicle

/**
  * BEAM
  */
object RoutingModel {

  type LegCostEstimator = BeamLeg => Option[Double]

  case class BeamTrip(legs: Vector[BeamLeg], accessMode: BeamMode)

  object BeamTrip {
    def apply(legs: Vector[BeamLeg]): BeamTrip = BeamTrip(legs, legs.head.mode)

    val empty: BeamTrip = BeamTrip(Vector(), BeamMode.WALK)
  }

  case class EmbodiedBeamTrip(legs: Vector[EmbodiedBeamLeg]) {

    lazy val costEstimate: BigDecimal = legs.map(_.cost).sum /// Generalize or remove
    lazy val tripClassifier: BeamMode = determineTripMode(legs)
    lazy val vehiclesInTrip: Vector[Id[Vehicle]] = determineVehiclesInTrip(legs)
    lazy val requiresReservationConfirmation: Boolean = tripClassifier!= WALK && legs.exists(!_.asDriver)

    val totalTravelTime: Long = legs.map(_.beamLeg.duration).sum

    def beamLegs(): Vector[BeamLeg] = legs.map(embodiedLeg => embodiedLeg.beamLeg)

    def toBeamTrip(): BeamTrip = BeamTrip(beamLegs())

    def determineTripMode(legs: Vector[EmbodiedBeamLeg]): BeamMode = {
      var theMode: BeamMode = WALK
      var hasUsedCar: Boolean = false
      legs.foreach { leg =>
        // Any presence of transit makes it transit
        if (leg.beamLeg.mode.isTransit) {
          theMode = TRANSIT
        } else if (theMode == WALK && leg.beamLeg.mode == CAR) {
          if((legs.size == 1 && legs(0).beamVehicleId.toString.contains("rideHailingVehicle")) ||
            (legs.size>1 && legs(1).beamVehicleId.toString.contains("rideHailingVehicle"))){
            theMode = RIDEHAIL
          }else{
            theMode = CAR
          }
        } else if (theMode == WALK && leg.beamLeg.mode == BIKE) {
          theMode = BIKE
        }
        if(leg.beamLeg.mode == CAR)hasUsedCar = true
      }
      if(theMode == TRANSIT && hasUsedCar){
        DRIVE_TRANSIT
      }else if(theMode == TRANSIT && !hasUsedCar){
        WALK_TRANSIT
      }else{
        theMode
      }
    }



    def determineVehiclesInTrip(legs: Vector[EmbodiedBeamLeg]): Vector[Id[Vehicle]] = {
      legs.map(leg => leg.beamVehicleId).distinct
    }
    override def toString() = {
      s"EmbodiedBeamTrip(${tripClassifier} starts ${legs.head.beamLeg.startTime} legModes ${legs.map(_.beamLeg.mode).mkString(",")})"
    }
  }

  object EmbodiedBeamTrip {

    def beamModeToVehicleId(beamMode: BeamMode): Id[Vehicle] = {
      if (beamMode == WALK) {
        Id.create("body", classOf[Vehicle])
      } else if (Modes.isR5TransitMode(beamMode)) {
        Id.create("transit", classOf[Vehicle])
      } else {
        Id.create("", classOf[Vehicle])
      }
    }

    val empty: EmbodiedBeamTrip = EmbodiedBeamTrip(Vector())
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
                     travelPath: BeamPath) {
    val endTime: Long = startTime + duration

    override def toString: String = s"BeamLeg(${mode} @ ${startTime},dur:${duration},path: ${travelPath.toShortString})"
  }

  object BeamLeg {
    val beamLegOrdering: Ordering[BeamLeg] = Ordering.by(x=>(x.startTime,x.duration))

    def dummyWalk(startTime: Long): BeamLeg = new BeamLeg(startTime, WALK, 0, BeamPath(Vector(), None, SpaceTime.zero, SpaceTime.zero, 0))
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

  case class TransitStopsInfo(fromStopId: Int, vehicleId: Id[Vehicle], toStopId: Int)

  /**
    *
    * @param linkIds either matsim linkId or R5 edgeIds that describes whole path
    * @param transitStops start and end stop if this path is transit (partial) route

    */
  case class BeamPath(linkIds: Vector[String], transitStops: Option[TransitStopsInfo], startPoint: SpaceTime, endPoint: SpaceTime, distanceInM: Double) {

    def toShortString() = if(linkIds.size >0){ s"${linkIds.head} .. ${linkIds(linkIds.size - 1)}"}else{""}

  }

  //case object EmptyBeamPath extends BeamPath(Vector[String](), None, departure = SpaceTime(Double.PositiveInfinity, Double.PositiveInfinity, Long.MaxValue), arrival = SpaceTime(Double.NegativeInfinity, Double.NegativeInfinity, Long.MinValue))
  object EmptyBeamPath {
    val path = BeamPath(Vector[String](), None, null, null, 0)
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

