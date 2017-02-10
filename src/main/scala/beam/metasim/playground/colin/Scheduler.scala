package beam.metasim.playground.colin

import akka.actor.Actor
import akka.event.Logging
import akka.util.Timeout
import akka.pattern.ask

import scala.collection.mutable
import scala.collection.mutable
import scala.math.Ordered.orderingToOrdered
import scala.concurrent.duration._
import java.util.Deque

import com.google.common.collect.TreeMultimap

sealed trait SchedulerMessage
case object StartSchedule extends SchedulerMessage
case class DoSimStep(tick: Double) extends SchedulerMessage
case class CompletionNotice(triggerCompleted: Trigger) extends SchedulerMessage

object Scheduler {
}
class Scheduler extends Actor {
  val log = Logging(context.system, this)
  val maxWindow = 10.0
  var triggerQueue = new mutable.PriorityQueue[Trigger]()
  var awaitingResponse = new TreeMultimap[Double,Int]()
  var idCount: Int = 0

  def receive = {
    case StartSchedule => {
      log.info("starting scheduler")
      self ! DoSimStep(0.0)
    }
    case DoSimStep(now: Double) ⇒ {
      if(now - awaitingResponse.keySet().first() < maxWindow) {
        while (triggerQueue.nonEmpty & triggerQueue.head.data.tick <= now) {
          val trigger = this.triggerQueue.dequeue
          log.info("dispatching event at tick " + trigger.data.tick)
          awaitingResponse.put(trigger.data.tick, trigger.data.id)
          trigger.data.agent ! trigger
        }
        self ! DoSimStep(now + 1.0)
      }else{
        Thread.sleep(10)
        DoSimStep(now)
      }
    }
    case triggerData: TriggerData => {
      log.info("recieved notice that trigger id: "+triggerData.id+" is complete")
      awaitingResponse.remove(triggerData.tick,triggerData.id)
    }
    case trigger: Trigger => {
      this.idCount += 1
      trigger.data.id = this.idCount
      triggerQueue.enqueue(trigger)
      log.info("recieved trigger to schedule "+trigger)
    }
    case _      ⇒ log.info("received unknown message")
  }
}