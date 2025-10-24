package beam.router.model

import beam.router.r5.TravelTimeByLinkCalculator
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
      val links = leg.travelPath.linkIds
      val fullyTraversedLinks = links.drop(1).dropRight(1)

      def exitTimeByEnterTimeAndLinkId(enterTime: Int, linkId: Int) =
        enterTime + travelTimeByEnterTimeAndLinkId(enterTime, linkId)

      val timesAtNodes = fullyTraversedLinks.scanLeft(leg.startTime)(exitTimeByEnterTimeAndLinkId)
      val events = new ArrayBuffer[Event]()
      links.sliding(2).zip(timesAtNodes.iterator).foreach { case (Array(from, to), timeAtNode) =>
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
    travelTimeByEnterTimeAndLinkId: TravelTimeByLinkCalculator,
    mode: StreetMode,
    streetLayer: StreetLayer
  ): LinksTimesDistances = {

    val numLinks = linkIds.length
    val traversalTimes = new Array[Double](numLinks)
    val distances = new Array[Double](numLinks)

    // Manual loop - no intermediate collections, no boxing
    var currentTime = startTime.toDouble
    var i = 0
    while (i < numLinks) {
      val linkId = linkIds(i)
      val travelTime = travelTimeByEnterTimeAndLinkId(currentTime, linkId, mode)
      val exitTime = currentTime + travelTime

      traversalTimes(i) = Math.max(exitTime - currentTime, 0.0)
      distances(i) = streetLayer.edgeStore.getCursor(linkId).getLengthM

      currentTime = exitTime
      i += 1
    }

    LinksTimesDistances(linkIds.toArray, traversalTimes, distances)
  }

  case class LinksTimesDistances(
    linkIds: Array[Int], // Primitive int[]
    travelTimes: Array[Double], // Primitive double[]
    distances: Array[Double] // Primitive double[]
  )

  case class TransitStopsInfo(
    agencyId: String,
    routeId: String,
    vehicleId: Id[Vehicle],
    fromIdx: Int,
    toIdx: Int
  )

}
