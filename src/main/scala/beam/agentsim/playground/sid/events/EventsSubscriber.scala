package beam.agentsim.playground.sid.events

import akka.actor.Actor
import akka.event.Logging
import beam.agentsim.playground.sid.events.EventsSubscriber.{FinishProcessing, StartProcessing}
import beam.agentsim.playground.sid.events.MetasimEventsBus.MetaSimEvent
import org.matsim.core.api.experimental.events.EventsManager

object EventsSubscriber{
  case object StartProcessing
  case object FinishProcessing
}

class EventsSubscriber (private val eventsManager: EventsManager) extends Actor {
  val log = Logging(context.system, this)
  type Event = org.matsim.api.core.v01.events.Event
  def receive: Receive = {

    case StartProcessing =>
      eventsManager.initProcessing()

    case event: Event =>
      eventsManager.processEvent(event)
      log.info(s"${self.toString()} received ${event.getEventType} event!" )

    case FinishProcessing =>
      eventsManager.finishProcessing()

    case _ => log.info("received unknown message")
  }
}
