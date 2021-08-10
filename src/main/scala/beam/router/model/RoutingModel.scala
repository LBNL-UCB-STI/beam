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
      links.sliding(2).zip(timesAtNodes.iterator).foreach { case (Seq(from, to), timeAtNode) =>
        events += new LinkLeaveEvent(timeAtNode, vehicleId, Id.createLinkId(from))
        events += new LinkEnterEvent(timeAtNode, vehicleId, Id.createLinkId(to))
      }
      events.toIterator
    } else {
      Iterator.empty
    }
  }

  def linksToTimeAndDistance(
    linkIds: IndexedSeq[Int],
    startTime: Int,
    travelTimeByEnterTimeAndLinkId: (Double, Int, StreetMode) => Double,
    mode: StreetMode,
    streetLayer: StreetLayer
  ): LinksTimesDistances = {
    def exitTimeByEnterTimeAndLinkId(enterTime: Double, linkId: Int): Double =
      enterTime + travelTimeByEnterTimeAndLinkId(enterTime, linkId, mode)

    val traversalTimes = linkIds.view
      .scanLeft(startTime.toDouble)(exitTimeByEnterTimeAndLinkId)
      .sliding(2)
      .map(pair => pair.last - pair.head)
      .toVector
    val cumulDistance =
      linkIds.map(streetLayer.edgeStore.getCursor(_).getLengthM)
    LinksTimesDistances(linkIds, traversalTimes, cumulDistance)
  }

  case class LinksTimesDistances(
    linkIds: IndexedSeq[Int],
    travelTimes: Vector[Double],
    distances: IndexedSeq[Double]
  )

  case class TransitStopsInfo(
    agencyId: String,
    routeId: String,
    vehicleId: Id[Vehicle],
    fromIdx: Int,
    toIdx: Int
  )

}
