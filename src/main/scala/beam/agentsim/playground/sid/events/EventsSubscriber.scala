package beam.agentsim.playground.sid.events

import akka.actor.Actor
import akka.event.Logging
import beam.agentsim.playground.sid.events.AgentsimEventsBus.MatsimEvent
import beam.agentsim.playground.sid.events.EventsSubscriber.{FinishProcessing, ResetHandler, StartProcessing}
import org.matsim.core.api.experimental.events.EventsManager

object EventsSubscriber{
  case object StartProcessing
  case class ResetHandler(getIteration: Int)
  case object FinishProcessing
}

class EventsSubscriber (private val eventsManager: EventsManager) extends Actor {
  val log = Logging(context.system, this)
  type Event = MatsimEvent

  def receive: Receive = {

    case StartProcessing =>
      eventsManager.initProcessing()

    case event: Event =>
      eventsManager.processEvent(event.wrappedEvent)
      log.info(s"${self.toString()} received ${event.wrappedEvent.getEventType} event!" )

    case FinishProcessing =>
      eventsManager.finishProcessing()

    case ResetHandler(iter) =>
      eventsManager.resetHandlers(iter)


    case _ => log.info("received unknown message")
  }
}
