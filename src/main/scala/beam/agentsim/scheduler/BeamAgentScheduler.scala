package beam.agentsim.scheduler

import java.lang.Double

import akka.actor.{Actor, ActorRef, Props}
import akka.event.Logging
import beam.agentsim.scheduler.BeamAgentScheduler._
import com.google.common.collect.TreeMultimap

import scala.collection.mutable

object BeamAgentScheduler {
  sealed trait SchedulerMessage
  case class StartSchedule() extends SchedulerMessage
  case class DoSimStep(tick: Double) extends SchedulerMessage
  case class CompletionNotice(id: Long, newTriggers: Vector[ScheduleTrigger] = Vector[ScheduleTrigger]()) extends SchedulerMessage
  case class ScheduleTrigger(trigger: Trigger, agent: ActorRef, priority: Int = 0) extends SchedulerMessage{
  def completed(triggerId: Long, scheduleTriggers: Vector[ScheduleTrigger]): CompletionNotice = {
    CompletionNotice(triggerId, scheduleTriggers)
  }
//    require(trigger.tick>=0, "Negative ticks not supported!")
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
  def SchedulerProps(stopTick: Double = 3600.0*24.0, maxWindow: Double = 1.0): Props = {
    Props(classOf[BeamAgentScheduler],stopTick, maxWindow)
  }
}

class BeamAgentScheduler(val stopTick: Double, val maxWindow: Double) extends Actor {
  val log = Logging(context.system, this)
  var triggerQueue = new mutable.PriorityQueue[ScheduledTrigger]()
  var awaitingResponse: TreeMultimap[java.lang.Double, java.lang.Long] = TreeMultimap.create[java.lang.Double, java.lang.Long]()
  val triggerIdToTick: mutable.Map[Long, Double] = scala.collection.mutable.Map[Long,java.lang.Double]()
  var idCount: Long = 0L
  var startSender: ActorRef = self
  var now: Double = 0.0

  def scheduleTrigger(triggerToSchedule: ScheduleTrigger): Unit = {
    this.idCount += 1
    if(now - triggerToSchedule.trigger.tick > maxWindow){
      throw new RuntimeException(s"Cannot schedule an event $triggerToSchedule at tick ${triggerToSchedule.trigger.tick} when 'now' is at $now sender=${sender()}")
    }
    val triggerWithId = TriggerWithId(triggerToSchedule.trigger, this.idCount)
    triggerQueue.enqueue(ScheduledTrigger(triggerWithId, triggerToSchedule.agent, triggerToSchedule.priority ))
    triggerIdToTick += (triggerWithId.triggerId -> triggerToSchedule.trigger.tick)
//    log.info(s"recieved trigger to schedule $triggerToSchedule")
  }

  def receive: Receive = {
    case StartSchedule() =>
      log.info("starting scheduler")
      this.startSender = sender()
      self ! DoSimStep(0.0)

    case DoSimStep(newNow: Double) if newNow <= stopTick =>
      now = newNow
      if(now >=13547){
        val i = 0
      }
      if (awaitingResponse.isEmpty || now - awaitingResponse.keySet().first() + 1 < maxWindow) {
        while (triggerQueue.nonEmpty && triggerQueue.head.triggerWithId.trigger.tick <= now) {
          val scheduledTrigger = this.triggerQueue.dequeue
          val triggerWithId = scheduledTrigger.triggerWithId
          //log.info(s"dispatching $triggerWithId")
          awaitingResponse.put(triggerWithId.trigger.tick, triggerWithId.triggerId)
          scheduledTrigger.agent ! triggerWithId
        }
        if(now%1800 == 0)log.info("Hour "+now/3600.0+" completed.")
        if (awaitingResponse.isEmpty || (now + 1) - awaitingResponse.keySet().first() + 1 < maxWindow) {
          self ! DoSimStep(now + 1.0)
        }else{
          Thread.sleep(10)
          self ! DoSimStep(now)
        }
      } else {
        Thread.sleep(10)
        self ! DoSimStep(now)
      }

    case DoSimStep(newNow: Double) if newNow > stopTick =>
      now = newNow
      if (awaitingResponse.isEmpty) {
        log.info(s"Stopping BeamAgentScheduler @ tick $now")
        startSender ! CompletionNotice(0L)
      }else {
        Thread.sleep(10)
        self ! DoSimStep(now)
      }

    case CompletionNotice(id: Long, newTriggers: Vector[ScheduleTrigger]) =>
//      log.info(s"recieved notice that trigger id: $id is complete")
      newTriggers.foreach{scheduleTrigger}
      awaitingResponse.remove(triggerIdToTick(id), id)
      triggerIdToTick -= id

    case triggerToSchedule: ScheduleTrigger => scheduleTrigger(triggerToSchedule)

    case msg => log.info(s"received unknown message: $msg")
  }

}
