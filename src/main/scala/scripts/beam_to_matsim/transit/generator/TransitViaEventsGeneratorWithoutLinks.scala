package beam.utils.beam_to_matsim.transit.generator

import beam.sim.common.GeoUtils
import beam.utils.beam_to_matsim.events.BeamPathTraversal
import beam.utils.beam_to_matsim.transit.{TransitEventsGroup, TransitHelper}
import beam.utils.beam_to_matsim.via_event.ViaEvent
import beam.utils.{NetworkHelper, NetworkHelperImpl}
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.network.NetworkUtils
import org.matsim.core.network.io.MatsimNetworkReader

private[transit] trait TransitViaEventsGeneratorWithoutLinks extends TransitViaEventsGenerator {
  import TransitHelper._

  def networkHelper: NetworkHelper

  override def generator(idleThresholdInSec: Double, rangeSize: Int)(
    events: Vector[BeamPathTraversal]
  ): Vector[ViaEvent] = {
    val linkStartAndEndMap: Map[(Coord, Coord), Id[Link]] = networkHelper.allLinks.map { link =>
      val fromNode = link.getFromNode
      val toNode = link.getToNode
      val from = GeoUtils.GeoUtilsNad83.utm2Wgs(fromNode.getCoord)
      val to = GeoUtils.GeoUtilsNad83.utm2Wgs(toNode.getCoord)
      val startAndEnd = (new Coord(round(from.getX), round(from.getY)), new Coord(round(to.getX), round(to.getY)))
      startAndEnd -> link.getId
    }.toMap
    val groupedEvents = TransitEventsGroup.groupEvents(rangeSize, events)
    val xss = groupedEvents.map { case (range, trips) =>
      val event = trips.head
      val prefix = prefixVehicleId(event)
      prefix -> createViaEvents(trips, createVehicleId(prefix, range), Some(linkStartAndEndMap))
    }
    insertIdleEvents(idleThresholdInSec, xss)
  }
}

private[transit] case class CommonViaEventsGeneratorWithoutLinks(networkPath: String)
    extends TransitViaEventsGeneratorWithoutLinks {

  lazy val networkHelper: NetworkHelper = {
    val temp = NetworkUtils.createNetwork()
    new MatsimNetworkReader(temp).readFile(networkPath)
    new NetworkHelperImpl(temp)
  }
}
