package beam.metasim.agents

import akka.actor.Actor
import akka.event.Logging
import com.google.common.collect.TreeMultimap

import scala.collection.mutable

sealed trait SchedulerMessage
case class StartSchedule(stopTick: Double, maxWindow: Double) extends SchedulerMessage
case class DoSimStep(tick: Double) extends SchedulerMessage
case class CompletionNotice(triggerData: TriggerData) extends SchedulerMessage

object BeamAgentScheduler {
}
class BeamAgentScheduler extends Actor {
  val log = Logging(context.system, this)
  var triggerQueue = new mutable.PriorityQueue[Trigger]()
  var awaitingResponse = TreeMultimap.create[java.lang.Double,java.lang.Integer]();
  var idCount: Int = 0
  var stopTick: Double = 0.0
  var maxWindow: Double = 0.0

  def receive = {
    case StartSchedule(stopTick: Double, maxWindow: Double) => {
      log.info("starting scheduler")
      this.stopTick = stopTick
      this.maxWindow = maxWindow
      self ! DoSimStep(0.0)
    }
    case DoSimStep(now: Double) => {
      if(now <= stopTick) {
        if (awaitingResponse.isEmpty || now - awaitingResponse.keySet().first() < maxWindow) {
          while (triggerQueue.nonEmpty && triggerQueue.head.triggerData.tick <= now) {
            val trigger = this.triggerQueue.dequeue
            log.info("dispatching event at tick " + trigger.triggerData.tick)
            awaitingResponse.put(trigger.triggerData.tick, trigger.triggerData.id)
            trigger.triggerData.agent ! trigger
          }
          self ! DoSimStep(now + 1.0)
        } else {
          Thread.sleep(10)
          DoSimStep(now)
        }
      }
    }
    case CompletionNotice(triggerData: TriggerData) => {
      log.info("recieved notice that trigger id: "+triggerData.id+" is complete")
      awaitingResponse.remove(triggerData.tick,triggerData.id)
    }
    case trigger: Trigger => {
      this.idCount += 1
      trigger.triggerData.id = this.idCount
      triggerQueue.enqueue(trigger)
      log.info("recieved trigger to schedule "+trigger)
    }
    case _      => log.info("received unknown message")
  }
}