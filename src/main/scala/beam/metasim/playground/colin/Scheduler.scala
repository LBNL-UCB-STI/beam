package beam.metasim.playground.colin

import akka.actor.Actor
import akka.event.Logging
import scala.collection.mutable.PriorityQueue
import java.util.Deque

object Scheduler {
}
class Scheduler extends Actor {
  val log = Logging(context.system, this)
  var eventQueue = new PriorityQueue[BeamEvent]()

  def receive = {
    case "start" ⇒ {
      log.info("starting scheduler")
      while(!this.eventQueue.isEmpty){
        val event = this.eventQueue.dequeue
        log.info("dispatching event " + event.tick)
        event.agent ! event.msg
      }
    }
    case event:BeamEvent => {
      eventQueue.enqueue(event)
      log.info("recieved event to schedule "+event.tick)
    }
    case _      ⇒ log.info("received unknown message")
  }
}