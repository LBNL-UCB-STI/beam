package beam.agentsim.events

import akka.actor.Actor
import akka.event.Logging
import beam.agentsim.events.AgentsimEventsBus.MatsimEvent
import org.matsim.core.api.experimental.events.EventsManager

object EventsSubscriber{
  sealed trait SubscriberEvent
  case object StartProcessing extends SubscriberEvent
  case class StartIteration(iteration: Int) extends SubscriberEvent
  case class EndIteration(iteration: Int) extends SubscriberEvent
  case object FinishProcessing extends SubscriberEvent
  case object ProcessingFinished extends SubscriberEvent
  case object FileWritten extends SubscriberEvent
}

class EventsSubscriber (private val eventsManager: EventsManager) extends Actor {
  val log = Logging(context.system, this)

  type Event = MatsimEvent


  def receive: Receive = {

    case event: Event =>
      try {
        eventsManager.processEvent(event.wrappedEvent)
      }catch {
        case e:IllegalArgumentException =>
          log.error(s"$e")
      }

    case _ => log.info("received unknown message")
  }
}


