package beam.agentsim.events

import akka.actor.{Actor, ActorLogging}
import org.matsim.api.core.v01.events.Event
import org.matsim.core.api.experimental.events.EventsManager

object EventsSubscriber{
  val SUBSCRIBER_NAME:String = "MATSIMEventsSubscriber"
}



