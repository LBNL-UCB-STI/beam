package beam.agentsim.agents

import akka.actor.Actor
import akka.event.Logging
import akka.actor.ActorRef
import com.google.common.collect.TreeMultimap

import scala.collection.mutable

sealed trait SchedulerMessage

case class StartSchedule(stopTick: Double, maxWindow: Double) extends SchedulerMessage
case class DoSimStep(tick: Double) extends SchedulerMessage
case class CompletionNotice(id: Long) extends SchedulerMessage
case class ScheduleTrigger(trigger: Trigger, agent: ActorRef, priority: Int = 0) extends SchedulerMessage{
  require(trigger.tick>=0, "Negative ticks not supported!")
}
case class ScheduledTrigger(triggerWithId: TriggerWithId, agent: ActorRef, priority: Int) extends Ordered[ScheduledTrigger] {
  // Compare is on 3 levels with higher priority (i.e. front of the queue) for:
  //   smaller tick => then higher priority value => then lower triggerId
  def compare(that: ScheduledTrigger): Int =
    (that.triggerWithId.trigger.tick compare triggerWithId.trigger.tick) match {
      case 0 =>
        (priority compare that.priority) match {
          case 0 =>
            (that.triggerWithId.triggerId compare triggerWithId.triggerId)
          case c => c
        }
      case c => c
    }
}

object BeamAgentScheduler {
}

class BeamAgentScheduler extends Actor {
  val log = Logging(context.system, this)
  var triggerQueue = new mutable.PriorityQueue[ScheduledTrigger]()
  var awaitingResponse: TreeMultimap[java.lang.Double, java.lang.Long] = TreeMultimap.create[java.lang.Double, java.lang.Long]()
  val triggerIdToTick = scala.collection.mutable.Map[Long,java.lang.Double]()
  var idCount: Long = 0L
  var stopTick: Double = 0.0
  var maxWindow: Double = 0.0

  def receive: Receive = {
    case StartSchedule(stopTick: Double, maxWindow: Double) =>
      log.info("starting scheduler")
      this.stopTick = stopTick
      this.maxWindow = maxWindow
      self ! DoSimStep(0.0)

    case DoSimStep(now: Double) if now <= stopTick =>
      val whatisthis = if (awaitingResponse.isEmpty) { -1 } else {awaitingResponse.keySet().first()}
      if (awaitingResponse.isEmpty || now - awaitingResponse.keySet().first() < maxWindow) {
        while (triggerQueue.nonEmpty && triggerQueue.head.triggerWithId.trigger.tick <= now) {
          val scheduledTrigger = this.triggerQueue.dequeue
          val triggerWithId = scheduledTrigger.triggerWithId
          log.info("dispatching " + triggerWithId)
          awaitingResponse.put(triggerWithId.trigger.tick, triggerWithId.triggerId)
          scheduledTrigger.agent ! triggerWithId
        }
        self ! DoSimStep(now + 1.0)
      } else {
        Thread.sleep(10)
        self ! DoSimStep(now)
      }

    case DoSimStep(now: Double) if now > stopTick =>
      log.info("Stopping BeamAgentScheduler @ tick "+now)

    case CompletionNotice(id: Long) =>
      log.info("recieved notice that trigger id: " + id + " is complete")
      awaitingResponse.remove(triggerIdToTick(id), id)
      triggerIdToTick -= id

    case triggerToSchedule: ScheduleTrigger =>
      this.idCount += 1
      val triggerWithId = TriggerWithId(triggerToSchedule.trigger, this.idCount)
      triggerQueue.enqueue(ScheduledTrigger(triggerWithId, triggerToSchedule.agent, triggerToSchedule.priority ))
      triggerIdToTick += (triggerWithId.triggerId -> triggerToSchedule.trigger.tick)
      log.info("recieved trigger to schedule " + triggerToSchedule)

    case msg => log.info("received unknown message: " + msg)
  }
}
