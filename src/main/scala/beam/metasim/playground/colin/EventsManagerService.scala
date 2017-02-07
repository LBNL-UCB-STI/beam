package beam.metasim.playground.colin

import akka.actor.Actor
import akka.event.Logging
import scala.collection.mutable.PriorityQueue
import java.util.Deque
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.api.core.v01.events.Event

case class SetEventsManager(manager: EventsManager)

class EventsManagerService extends Actor {
  val log = Logging(context.system, this)
  var manager: EventsManager = null
 
  def receive = {
    case SetEventsManager(manager: EventsManager) ⇒ {
      log.info("setting events manager scheduler")
      this.manager = manager
    }
    case event:org.matsim.api.core.v01.events.Event => {
      log.info("recieved event to process " + event)
      this.manager.processEvent(event)
    }
    case _      ⇒ log.info("received unknown message")
  }
}