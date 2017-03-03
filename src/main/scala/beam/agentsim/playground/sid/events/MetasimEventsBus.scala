package beam.agentsim.playground.sid.events

import akka.actor.ActorRef
import akka.event.{ActorEventBus, LookupClassification}


/**
  * Created by sfeygin on 2/11/17.
  */

object MetasimEventsBus{
  case class AddEventSubscriber(ref: ActorRef)
  case class RemoveEventSubscriber(ref: ActorRef)
}

class MetasimEventsBus extends ActorEventBus with LookupClassification {

  override type Event = org.matsim.api.core.v01.events.Event
  override type Classifier = String

  //  Closest number of classifiers as power of 2 hint.
  override protected def mapSize(): Int = 16

  override protected def classify(event: Event): String = {
    event.getEventType
  }

  override protected def publish(event: Event, subscriber: ActorRef): Unit = {
    subscriber ! event
  }
}
