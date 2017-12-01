package beam.agentsim.events

import akka.actor.{Actor, ActorLogging}
import beam.agentsim.events.AgentsimEventsBus.MatsimEvent
import beam.agentsim.events.EventsSubscriber.{EndIteration, ProcessingFinished}
import org.matsim.core.api.experimental.events.EventsManager

object EventsSubscriber{
  val SUBSCRIBER_NAME:String = "MATSIMEventsSubscriber"

  trait SubscriberEvent
  case object StartProcessing extends SubscriberEvent
  case class StartIteration(iteration: Int) extends SubscriberEvent
  case class EndIteration(iteration: Int) extends SubscriberEvent
  case class AfterSimStep(when: Int) extends SubscriberEvent
  case class FinishProcessing(iteration: Int) extends SubscriberEvent
  case class ProcessingFinished(iteration: Int) extends SubscriberEvent
  case object FileWritten extends SubscriberEvent
}

class EventsSubscriber (private val eventsManager: EventsManager) extends Actor with ActorLogging {

  type Event = MatsimEvent

  def receive: Receive = {
    case event: Event =>
      eventsManager.processEvent(event.wrappedEvent)
    case EndIteration(it) =>
      sender() ! ProcessingFinished(it)
  }

}


