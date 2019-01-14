package beam.agentsim.events
import akka.actor.{Actor, Props}
import beam.agentsim.events.ActorEventsManager.Message.ProcessLinkEvents
import beam.router.model.BeamLeg
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.{Event, LinkEnterEvent, LinkLeaveEvent}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.vehicles.Vehicle

object ActorEventsManager {
  sealed trait Message
  object Message {
    case class ProcessLinkEvents(vehicleId: Id[Vehicle], leg: BeamLeg) extends Message
  }

  def props(eventsManager: EventsManager): Props = Props(new ActorEventsManager(eventsManager))
}
class ActorEventsManager(val eventsManager: EventsManager) extends Actor {
   def receive: Receive = {
     case e:Event =>
       eventsManager.processEvent(e)
     case ple: ProcessLinkEvents =>
       processLinkEvents(ple.vehicleId, ple.leg)
  }

  def processLinkEvents(vehicleId: Id[Vehicle], leg: BeamLeg): Unit = {
    val path = leg.travelPath
    if (path.linkTravelTime.nonEmpty) {
      // FIXME once done with debugging, make this code faster
      // We don't need the travel time for the last link, so we drop it (dropRight(1))
      val avgTravelTimeWithoutLast = path.linkTravelTime.dropRight(1)
      val links = path.linkIds
      val linksWithTime = links.sliding(2).zip(avgTravelTimeWithoutLast.iterator)

      var curTime = leg.startTime
      linksWithTime.foreach {
        case (Seq(from, to), timeAtNode) =>
          curTime = curTime + timeAtNode
          eventsManager.processEvent(new LinkLeaveEvent(curTime, vehicleId, Id.createLinkId(from)))
          eventsManager.processEvent(new LinkEnterEvent(curTime, vehicleId, Id.createLinkId(to)))
      }
    }
  }
}
