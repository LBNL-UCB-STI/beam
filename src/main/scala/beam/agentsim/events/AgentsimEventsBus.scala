package beam.agentsim.events

import akka.actor.ActorRef
import akka.event.{ActorEventBus, LookupClassification}
import beam.agentsim.events.AgentsimEventsBus.MatsimEvent


/**
  * Created by sfeygin on 2/11/17.
  */

object AgentsimEventsBus{
  case class AddEventSubscriber(ref: ActorRef)
  case class RemoveEventSubscriber(ref: ActorRef)
  case class MatsimEvent(wrappedEvent:org.matsim.api.core.v01.events.Event)
}

class AgentsimEventsBus extends ActorEventBus with LookupClassification {

  override type Event = MatsimEvent
  override type Classifier = String
  override type Subscriber = ActorRef


  //  Closest number of classifiers as power of 2 hint.
  override protected def mapSize(): Int = 16

  override protected def classify(event: Event): Classifier = {
    event.wrappedEvent.getEventType
  }

  override protected def publish(event: Event, subscriber: Subscriber): Unit = {
    subscriber ! event
  }


}
