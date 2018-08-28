package beam.router

//import beam.agentsim.agents.vehicles.BeamVehicleType.{HumanBodyVehicle, RideHailVehicle}
import beam.agentsim.agents.vehicles.{BeamVehicleType, PassengerSchedule}
import beam.agentsim.events.SpaceTime
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{BIKE, CAR, DRIVE_TRANSIT, RIDE_HAIL, RIDE_HAIL_TRANSIT, TRANSIT, WALK, WALK_TRANSIT}
import com.conveyal.r5.profile.StreetMode
import com.conveyal.r5.streets.StreetLayer
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.{Event, LinkEnterEvent, LinkLeaveEvent}
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
    lazy val requiresReservationConfirmation: Boolean = tripClassifier != WALK && legs.exists(
      !_.asDriver
    )

    val totalTravelTimeInSecs: Long = legs.map(_.beamLeg.duration).sum

    def beamLegs(): Vector[BeamLeg] =
      legs.map(embodiedLeg => embodiedLeg.beamLeg)

    def toBeamTrip: BeamTrip = BeamTrip(beamLegs())

    def determineTripMode(legs: Vector[EmbodiedBeamLeg]): BeamMode = {
      var theMode: BeamMode = WALK
      var hasUsedCar: Boolean = false
      var hasUsedRideHail: Boolean = false
      legs.foreach { leg =>
        // Any presence of transit makes it transit
        if (leg.beamLeg.mode.isTransit) {
          theMode = TRANSIT
        } else if (theMode == WALK && leg.isRideHail) {
          theMode = RIDE_HAIL
        } else if (theMode == WALK && leg.beamLeg.mode == CAR) {
          theMode = CAR
        } else if (theMode == WALK && leg.beamLeg.mode == BIKE) {
          theMode = BIKE
        }
        if (leg.beamLeg.mode == CAR) hasUsedCar = true
        if (leg.isRideHail) hasUsedRideHail = true
      }
      if (theMode == TRANSIT && hasUsedRideHail) {
        RIDE_HAIL_TRANSIT
      } else if (theMode == TRANSIT && hasUsedCar) {
        DRIVE_TRANSIT
      } else if (theMode == TRANSIT && !hasUsedCar) {
        WALK_TRANSIT
      } else {
        theMode
      }
    }

    def determineVehiclesInTrip(legs: Vector[EmbodiedBeamLeg]): Vector[Id[Vehicle]] = {
      legs.map(leg => leg.beamVehicleId).distinct
    }
    override def toString: String = {
      s"EmbodiedBeamTrip($tripClassifier starts ${legs.headOption
        .map(head => head.beamLeg.startTime)
        .getOrElse("empty")} legModes ${legs.map(_.beamLeg.mode).mkString(",")})"
    }
  }

  object EmbodiedBeamTrip {
    val empty: EmbodiedBeamTrip = EmbodiedBeamTrip(Vector())
  }

  /**
    *
    * @param startTime time in seconds from base midnight
    * @param mode BeamMode
    * @param duration  period in seconds
    * @param travelPath BeamPath
    */
  case class BeamLeg(startTime: Long, mode: BeamMode, duration: Long, travelPath: BeamPath) {
    val endTime: Long = startTime + duration

    def updateLinks(newLinks: Vector[Int]): BeamLeg =
      this.copy(travelPath = this.travelPath.copy(newLinks))

    def updateStartTime(newStartTime: Long): BeamLeg =
      this
        .copy(startTime = newStartTime, travelPath = this.travelPath.updateStartTime(newStartTime))

    override def toString: String =
      s"BeamLeg($mode @ $startTime,dur:$duration,path: ${travelPath.toShortString})"
  }

  object BeamLeg {

    def dummyWalk(startTime: Long): BeamLeg =
      new BeamLeg(startTime, WALK, 0, BeamPath(Vector(), None, SpaceTime.zero, SpaceTime.zero, 0))
  }

  case class EmbodiedBeamLeg(
    beamLeg: BeamLeg,
    beamVehicleId: Id[Vehicle],
    asDriver: Boolean,
    passengerSchedule: Option[PassengerSchedule],
    cost: BigDecimal,
    unbecomeDriverOnCompletion: Boolean
  ) {

    val isHumanBodyVehicle: Boolean =
      BeamVehicleType.isHumanVehicle(beamVehicleId)
    val isRideHail: Boolean = BeamVehicleType.isRidehailVehicle(beamVehicleId)
  }

  def traverseStreetLeg(
    leg: BeamLeg,
    vehicleId: Id[Vehicle],
    travelTimeByEnterTimeAndLinkId: (Long, Int) => Long
  ): Iterator[Event] = {
    if (leg.travelPath.linkIds.size >= 2) {
      val fullyTraversedLinks = leg.travelPath.linkIds.drop(1).dropRight(1)
      def exitTimeByEnterTimeAndLinkId(enterTime: Long, linkId: Int) =
        enterTime + travelTimeByEnterTimeAndLinkId(enterTime, linkId)
      val timesAtNodes = fullyTraversedLinks.scanLeft(leg.startTime)(exitTimeByEnterTimeAndLinkId)
      leg.travelPath.linkIds.sliding(2).zip(timesAtNodes.iterator).flatMap {
        case (Seq(from, to), timeAtNode) =>
          Vector(
            new LinkLeaveEvent(timeAtNode, vehicleId, Id.createLinkId(from)),
            new LinkEnterEvent(timeAtNode, vehicleId, Id.createLinkId(to))
          )
      }
    } else {
      Iterator.empty
    }
  }

  def linksToTimeAndDistance(
    linkIds: Vector[Int],
    startTime: Long,
    travelTimeByEnterTimeAndLinkId: (Long, Int, StreetMode) => Long,
    mode: StreetMode,
    streetLayer: StreetLayer
  ): LinksTimesDistances = {
    def exitTimeByEnterTimeAndLinkId(enterTime: Long, linkId: Int) =
      enterTime + travelTimeByEnterTimeAndLinkId(enterTime, linkId, mode)
    val traversalTimes = linkIds
      .scanLeft(startTime)(exitTimeByEnterTimeAndLinkId)
      .sliding(2)
      .map(pair => pair.last - pair.head)
      .toVector
    val cumulDistance =
      linkIds.map(streetLayer.edgeStore.getCursor(_).getLengthM)
    LinksTimesDistances(linkIds, traversalTimes, cumulDistance)
  }

  case class LinksTimesDistances(
    linkIds: Vector[Int],
    travelTimes: Vector[Long],
    distances: Vector[Double]
  )
  case class TransitStopsInfo(fromStopId: Int, vehicleId: Id[Vehicle], toStopId: Int)

  /**
    *
    * @param linkIds either matsim linkId or R5 edgeIds that describes whole path
    * @param transitStops start and end stop if this path is transit (partial) route
    */
  case class BeamPath(
    linkIds: Vector[Int],
    transitStops: Option[TransitStopsInfo],
    startPoint: SpaceTime,
    endPoint: SpaceTime,
    distanceInM: Double
  ) {
    def duration: Long = endPoint.time - startPoint.time

    def toShortString: String =
      if (linkIds.nonEmpty) {
        s"${linkIds.head} .. ${linkIds(linkIds.size - 1)}"
      } else { "" }

    def updateStartTime(newStartTime: Long): BeamPath =
      this.copy(
        startPoint = this.startPoint.copy(time = newStartTime),
        endPoint = this.endPoint.copy(time = newStartTime + this.duration)
      )

  }

  //case object EmptyBeamPath extends BeamPath(Vector[String](), None, departure = SpaceTime(Double.PositiveInfinity, Double.PositiveInfinity, Long.MaxValue), arrival = SpaceTime(Double.NegativeInfinity, Double.NegativeInfinity, Long.MinValue))
  object EmptyBeamPath {
    val path = BeamPath(Vector[Int](), None, null, null, 0)
  }

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
