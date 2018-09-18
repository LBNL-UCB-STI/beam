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

import scala.collection.mutable.ArrayBuffer

/**
  * BEAM
  */
object RoutingModel {

  type LegCostEstimator = BeamLeg => Option[Double]

  case class BeamTrip(legs: IndexedSeq[BeamLeg], accessMode: BeamMode)

  object BeamTrip {
    def apply(legs: IndexedSeq[BeamLeg]): BeamTrip = BeamTrip(legs, legs.head.mode)

    val empty: BeamTrip = BeamTrip(Vector(), BeamMode.WALK)
  }

  case class EmbodiedBeamTrip(legs: IndexedSeq[EmbodiedBeamLeg]) {

    @transient
    lazy val costEstimate: BigDecimal = legs.map(_.cost).sum /// Generalize or remove
    @transient
    lazy val tripClassifier: BeamMode = determineTripMode(legs)

    @transient
    lazy val vehiclesInTrip: IndexedSeq[Id[Vehicle]] = determineVehiclesInTrip(legs)

    @transient
    lazy val requiresReservationConfirmation: Boolean = tripClassifier != WALK && legs.exists(
      !_.asDriver
    )

    val totalTravelTimeInSecs: Int = legs.map(_.beamLeg.duration).sum

    def beamLegs(): IndexedSeq[BeamLeg] =
      legs.map(embodiedLeg => embodiedLeg.beamLeg)

    def toBeamTrip: BeamTrip = BeamTrip(beamLegs())

    def determineTripMode(legs: IndexedSeq[EmbodiedBeamLeg]): BeamMode = {
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

    def determineVehiclesInTrip(legs: IndexedSeq[EmbodiedBeamLeg]): IndexedSeq[Id[Vehicle]] = {
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
  case class BeamLeg(startTime: Int, mode: BeamMode, duration: Int, travelPath: BeamPath) {
    val endTime: Int = startTime + duration

    def updateLinks(newLinks: IndexedSeq[Int]): BeamLeg =
      this.copy(travelPath = this.travelPath.copy(newLinks))

    def updateStartTime(newStartTime: Int): BeamLeg = {
      val newTravelPath = this.travelPath.updateStartTime(newStartTime)
      this
        .copy(
          startTime = newStartTime,
          duration = newTravelPath.endPoint.time - newStartTime,
          travelPath = newTravelPath
        )

    }

    override def toString: String =
      s"BeamLeg($mode @ $startTime,dur:$duration,path: ${travelPath.toShortString})"
  }

  object BeamLeg {

    def dummyWalk(startTime: Int): BeamLeg =
      (new BeamLeg(0, WALK, 0, BeamPath(Vector(), None, SpaceTime.zero, SpaceTime.zero, 0))).updateStartTime(startTime)
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
    travelTimeByEnterTimeAndLinkId: (Int, Int) => Int
  ): Iterator[Event] = {
    if (leg.travelPath.linkIds.size >= 2) {
      val links = leg.travelPath.linkIds.view
      val fullyTraversedLinks = links.drop(1).dropRight(1)
      def exitTimeByEnterTimeAndLinkId(enterTime: Int, linkId: Int) =
        enterTime + travelTimeByEnterTimeAndLinkId(enterTime, linkId)
      val timesAtNodes = fullyTraversedLinks.scanLeft(leg.startTime)(exitTimeByEnterTimeAndLinkId)
      val events = new ArrayBuffer[Event]()
      links.sliding(2).zip(timesAtNodes.iterator).foreach {
        case (Seq(from, to), timeAtNode) =>
          events += new LinkLeaveEvent(timeAtNode, vehicleId, Id.createLinkId(from))
          events += new LinkEnterEvent(timeAtNode, vehicleId, Id.createLinkId(to))
      }
      events.toIterator
    } else {
      Iterator.empty
    }
  }

  def traverseStreetLeg_opt(leg: BeamLeg, vehicleId: Id[Vehicle]): Iterator[Event] = {
    if (leg.travelPath.linkIds.size >= 2) {
      val links = leg.travelPath.linkIds
      val eventsSize = 2 * (links.length - 1)
      val events = new Array[Event](eventsSize)
      var curr: Int = 0
      val timeAtNode = leg.startTime
      var arrIdx: Int = 0
      while (curr < links.length - 1) {
        val from = links(curr)
        val to = links(curr + 1)

        events.update(arrIdx, new LinkLeaveEvent(timeAtNode, vehicleId, Id.createLinkId(from)))
        arrIdx += 1

        events.update(arrIdx, new LinkEnterEvent(timeAtNode, vehicleId, Id.createLinkId(to)))
        arrIdx += 1

        curr += 1
      }
      events.toIterator
    } else {
      Iterator.empty
    }
  }

  def linksToTimeAndDistance(
    linkIds: IndexedSeq[Int],
    startTime: Int,
    travelTimeByEnterTimeAndLinkId: (Int, Int, StreetMode) => Int,
    mode: StreetMode,
    streetLayer: StreetLayer
  ): LinksTimesDistances = {
    def exitTimeByEnterTimeAndLinkId(enterTime: Int, linkId: Int): Int =
      enterTime + travelTimeByEnterTimeAndLinkId(enterTime, linkId, mode)
    val traversalTimes = linkIds.view
      .scanLeft(startTime)(exitTimeByEnterTimeAndLinkId)
      .sliding(2)
      .map(pair => pair.last - pair.head)
      .toVector
    val cumulDistance =
      linkIds.map(streetLayer.edgeStore.getCursor(_).getLengthM)
    LinksTimesDistances(linkIds, traversalTimes, cumulDistance)
  }

  case class LinksTimesDistances(
    linkIds: IndexedSeq[Int],
    travelTimes: Vector[Int],
    distances: IndexedSeq[Double]
  )
  case class TransitStopsInfo(fromStopId: Int, vehicleId: Id[Vehicle], toStopId: Int)

  /**
    *
    * @param linkIds either matsim linkId or R5 edgeIds that describes whole path
    * @param transitStops start and end stop if this path is transit (partial) route
    */
  case class BeamPath(
    linkIds: IndexedSeq[Int],
    transitStops: Option[TransitStopsInfo],
    startPoint: SpaceTime,
    endPoint: SpaceTime,
    distanceInM: Double
  ) {
    def duration: Int = endPoint.time - startPoint.time

    def toShortString: String =
      if (linkIds.nonEmpty) {
        s"${linkIds.head} .. ${linkIds(linkIds.size - 1)}"
      } else { "" }

    def updateStartTime(newStartTime: Int): BeamPath =
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
