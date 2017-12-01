package beam.agentsim.events

import akka.actor.{Actor, ActorLogging}
import beam.agentsim.events.AgentsimEventsBus.MatsimEvent
import org.matsim.core.api.experimental.events.EventsManager

object EventsSubscriber{
  val SUBSCRIBER_NAME:String = "MATSIMEventsSubscriber"
}

class EventsSubscriber (private val eventsManager: EventsManager) extends Actor with ActorLogging {

  type Event = MatsimEvent

  def receive: Receive = {
    case event: Event =>
      eventsManager.processEvent(event.wrappedEvent)
  }

}


