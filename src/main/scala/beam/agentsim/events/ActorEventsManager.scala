package beam.agentsim.events

import akka.actor.{Actor, ActorLogging, Props}
import beam.agentsim.events.ActorEventsManager.Message.{IterationEnd, IterationStart, ProcessLinkEvents}
import beam.router.model.BeamLeg
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.{Event, LinkEnterEvent, LinkLeaveEvent}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.vehicles.Vehicle

object ActorEventsManager {
  sealed trait Message

  object Message {
    case class ProcessLinkEvents(vehicleId: Id[Vehicle], leg: BeamLeg) extends Message
    case class IterationStart(iteration: Int) extends Message
    case class IterationEnd(iteration: Int) extends Message
  }

  def props(eventsManager: EventsManager): Props = Props(new ActorEventsManager(eventsManager))
}

class ActorEventsManager(val eventsManager: EventsManager) extends Actor with ActorLogging {
  var numberOfReceivedMatsimEvents: Int = 0
  var numberOfReceivedProcessLinkEvents: Int = 0

  def receive: Receive = {
    case e: Event =>
      numberOfReceivedMatsimEvents += 1
      eventsManager.processEvent(e)
    case ple: ProcessLinkEvents =>
      numberOfReceivedProcessLinkEvents += 1
      processLinkEvents(ple.vehicleId, ple.leg)
    case s: IterationStart =>
      numberOfReceivedMatsimEvents = 0
      numberOfReceivedProcessLinkEvents = 0
    case e: IterationEnd =>
      log.info(
        s"Iteration ${e.iteration}. Received $numberOfReceivedMatsimEvents MATSim events and $numberOfReceivedProcessLinkEvents ProcessLinkEvents"
      )
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
