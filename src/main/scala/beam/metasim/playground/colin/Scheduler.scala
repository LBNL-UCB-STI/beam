package beam.metasim.playground.colin

import akka.actor.Actor
import akka.event.Logging
import scala.collection.mutable.PriorityQueue
import java.util.Deque

object Scheduler {
}
class Scheduler extends Actor {
  val log = Logging(context.system, this)
  var eventQueue = new PriorityQueue[TriggerEvent]()

  def receive = {
    case "start" ⇒ {
      log.info("starting scheduler")
      var latestTick = -1.0
      while(!this.eventQueue.isEmpty){
        val event = this.eventQueue.dequeue
        log.info("dispatching event " + event.tick)
        latestTick = event.tick
        event.agent ! event.trigger
      }
    }
    case event:TriggerEvent => {
      eventQueue.enqueue(event)
      log.info("recieved event to schedule "+event)
    }
    case _      ⇒ log.info("received unknown message")
  }
}