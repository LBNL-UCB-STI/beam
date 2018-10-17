package beam.router.model

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

//  def traverseStreetLeg_opt(leg: BeamLeg, vehicleId: Id[Vehicle]): Iterator[Event] = {
//    if (leg.travelPath.linkIds.size >= 2) {
//      val links = leg.travelPath.linkIds
//      val eventsSize = 2 * (links.length - 1)
//      val events = new Array[Event](eventsSize)
//      var curr: Int = 0
//      val timeAtNode = leg.startTime
//      var arrIdx: Int = 0
//      while (curr < links.length - 1) {
//        val from = links(curr)
//        val to = links(curr + 1)
//
//        events.update(arrIdx, new LinkLeaveEvent(timeAtNode, vehicleId, Id.createLinkId(from)))
//        arrIdx += 1
//
//        events.update(arrIdx, new LinkEnterEvent(timeAtNode, vehicleId, Id.createLinkId(to)))
//        arrIdx += 1
//
//        curr += 1
//      }
//      events.toIterator
//    } else {
//      Iterator.empty
//    }
//  }

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
