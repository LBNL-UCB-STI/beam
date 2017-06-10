package beam.agentsim.scheduler

import java.lang.Double

import akka.actor.{Actor, ActorRef}
import akka.event.Logging
import com.google.common.collect.TreeMultimap

import scala.collection.mutable

object BeamAgentScheduler {
  sealed trait SchedulerMessage
  case class StartSchedule(stopTick: Double, maxWindow: Double) extends SchedulerMessage
  case class DoSimStep(tick: Double) extends SchedulerMessage
  case class CompletionNotice(id: Long, newTriggers: Vector[ScheduleTrigger] = Vector[ScheduleTrigger]()) extends SchedulerMessage
  case class ScheduleTrigger(trigger: Trigger, agent: ActorRef, priority: Int = 0) extends SchedulerMessage{
//    require(trigger.tick>=0, "Negative ticks not supported!")
  }
}

class BeamAgentScheduler extends Actor {
  val log = Logging(context.system, this)
  var triggerQueue = new mutable.PriorityQueue[ScheduledTrigger]()
  var awaitingResponse: TreeMultimap[java.lang.Double, java.lang.Long] = TreeMultimap.create[java.lang.Double, java.lang.Long]()
  val triggerIdToTick: mutable.Map[Long, Double] = scala.collection.mutable.Map[Long,java.lang.Double]()
  var idCount: Long = 0L
  var stopTick: Double = 0.0
  var maxWindow: Double = 0.0
  var startSender: ActorRef = self

  def scheduleTrigger(triggerToSchedule: ScheduleTrigger): Unit = {
    this.idCount += 1
    val triggerWithId = TriggerWithId(triggerToSchedule.trigger, this.idCount)
    triggerQueue.enqueue(ScheduledTrigger(triggerWithId, triggerToSchedule.agent, triggerToSchedule.priority ))
    triggerIdToTick += (triggerWithId.triggerId -> triggerToSchedule.trigger.tick)
//    log.info(s"recieved trigger to schedule $triggerToSchedule")
  }

  def receive: Receive = {
    case StartSchedule(stopTick: Double, maxWindow: Double) =>
      log.info("starting scheduler")
      this.startSender = sender()
      this.stopTick = stopTick
      this.maxWindow = maxWindow
      self ! DoSimStep(0.0)

    case DoSimStep(now: Double) if now <= stopTick =>
      if (awaitingResponse.isEmpty || now - awaitingResponse.keySet().first() < maxWindow) {
        while (triggerQueue.nonEmpty && triggerQueue.head.triggerWithId.trigger.tick <= now) {
          val scheduledTrigger = this.triggerQueue.dequeue
          val triggerWithId = scheduledTrigger.triggerWithId
          //log.info(s"dispatching $triggerWithId")
          awaitingResponse.put(triggerWithId.trigger.tick, triggerWithId.triggerId)
          scheduledTrigger.agent ! triggerWithId
        }
        if(now%1800 == 0)log.info("Hour "+now/3600.0+" completed.")
        self ! DoSimStep(now + 1.0)
      } else {
        Thread.sleep(10)
        self ! DoSimStep(now)
      }

    case DoSimStep(now: Double) if now > stopTick =>
      if (awaitingResponse.isEmpty) {
        log.info(s"Stopping BeamAgentScheduler @ tick $now")
        startSender ! CompletionNotice(0L)
      }else {
        Thread.sleep(10)
        self ! DoSimStep(now)
      }

    case CompletionNotice(id: Long, newTriggers: Vector[ScheduleTrigger]) =>
//      log.info(s"recieved notice that trigger id: $id is complete")
      awaitingResponse.remove(triggerIdToTick(id), id)
      triggerIdToTick -= id
      newTriggers.foreach {scheduleTrigger}

    case triggerToSchedule: ScheduleTrigger => scheduleTrigger(triggerToSchedule)

    case msg => log.info(s"received unknown message: $msg")
  }

  case class ScheduledTrigger(triggerWithId: TriggerWithId, agent: ActorRef, priority: Int) extends Ordered[ScheduledTrigger] {
    // Compare is on 3 levels with higher priority (i.e. front of the queue) for:
    //   smaller tick => then higher priority value => then lower triggerId
    def compare(that: ScheduledTrigger): Int =
    that.triggerWithId.trigger.tick compare triggerWithId.trigger.tick match {
      case 0 =>
        priority compare that.priority match {
          case 0 =>
            that.triggerWithId.triggerId compare triggerWithId.triggerId
          case c => c
        }
      case c => c
    }
  }
}
