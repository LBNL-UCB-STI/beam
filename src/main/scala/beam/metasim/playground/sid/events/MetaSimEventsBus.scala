package beam.metasim.playground.sid.events

import akka.actor.ActorRef
import akka.event.{ActorEventBus, LookupClassification}
import beam.metasim.playground.sid.events.MetaSimEventsBus.MetaSimEvent


/**
  * Created by sfeygin on 2/11/17.
  */

object MetaSimEventsBus{
  case class MetaSimEvent(topic:String, matsimEvent: org.matsim.api.core.v01.events.Event)
}

class MetaSimEventsBus extends ActorEventBus with LookupClassification {


  override type Event = MetaSimEvent
  override type Classifier = String

  //  Closest number of classifiers as power of 2 hint.
  override protected def mapSize(): Int = 16

  override protected def classify(event: Event): String = {
    event.topic
  }

  override protected def publish(event: Event, subscriber: ActorRef): Unit = {
    subscriber ! event
  }
}
