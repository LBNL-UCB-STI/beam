package beam.metasim.playground.sid.events

import akka.actor.Actor
import akka.event.Logging
import beam.metasim.playground.sid.events.EventsSubscriber.{FinishProcessing, StartProcessing}
import beam.metasim.playground.sid.events.MetaSimEventsBus.MetaSimEvent
import org.matsim.core.api.experimental.events.EventsManager

object EventsSubscriber{
  case object StartProcessing
  case object FinishProcessing
}
class EventsSubscriber (private val eventsManager: EventsManager, private val eventsBus: MetaSimEventsBus) extends Actor {
  val log = Logging(context.system, this)

  def receive: PartialFunction[Any, Unit] = {

    case StartProcessing =>
      eventsManager.initProcessing()

    case event: MetaSimEvent =>
      eventsManager.processEvent(event.matsimEvent)
      log.info(s"${self.toString()} received ${event.matsimEvent.getEventType} event to process on the ${event.topic} channel!" )

    case FinishProcessing =>
      eventsManager.finishProcessing()

    case _ => log.info("received unknown message")
  }
}
