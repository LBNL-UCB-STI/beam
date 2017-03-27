package beam.agentsim.routing.opentripplanner

import java.util

import org.matsim.api.core.v01.events.{Event, LinkEnterEvent, LinkLeaveEvent}
import org.matsim.api.core.v01.network.Network
import org.matsim.api.core.v01.{Id, events}
import org.matsim.core.population.routes.NetworkRoute
import org.matsim.core.router.util.TravelTime
import org.matsim.vehicles.Vehicle

/**
  * Created by sfeygin on 3/27/17.
  */
class RouteUtils {

}

object RouteUtils {
  private def calcRouteTravelTime(route: NetworkRoute, startTime: Double, travelTime: TravelTime, network: Network, eventQueue: util.Queue[Event], agentId: Id[Vehicle]) = {
    var tt = 0.0
    if (route.getStartLinkId ne route.getEndLinkId) {
      val startLink = route.getStartLinkId
      var linkEnterTime = startTime
      var linkEnterEvent = new events.LinkEnterEvent(0,Id.createVehicleId(0),Id.createLinkId(0))
      var linkLeaveEvent = new LinkLeaveEvent({
        linkEnterTime += 1; linkEnterTime
      }, agentId, startLink)
      eventQueue.add(linkLeaveEvent)
      var linkLeaveTime = linkEnterTime
      val routeLinkIds = route.getLinkIds
      import scala.collection.JavaConversions._
      for (routeLinkId <- routeLinkIds) {
        if (linkEnterTime > 1E16) {
          val mmm = 0
        }
        linkEnterTime = linkLeaveTime
        linkEnterEvent = new LinkEnterEvent(linkEnterTime, agentId, routeLinkId)
        eventQueue.add(linkEnterEvent)
        val linkTime = travelTime.getLinkTravelTime(network.getLinks.get(routeLinkId), linkEnterTime, null, null)
        tt += Math.max(linkTime, 1.0)
        linkLeaveTime = Math.max(linkEnterTime + 1, linkEnterTime + linkTime)
        linkLeaveEvent = new LinkLeaveEvent(linkLeaveTime, agentId, routeLinkId)
        eventQueue.add(linkLeaveEvent)
      }
      tt = linkLeaveTime - startTime
    }
    val linkEnterEvent = new LinkEnterEvent(startTime + tt, agentId, route.getEndLinkId)
    eventQueue.add(linkEnterEvent)
    tt + travelTime.getLinkTravelTime(network.getLinks.get(route.getEndLinkId), tt + startTime, null, null)
  }
}
