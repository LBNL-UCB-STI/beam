package beam.agentsim.scheduler

import java.lang.Double
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

import akka.actor.{Actor, ActorRef, Props}
import akka.event.Logging
import beam.agentsim.scheduler.BeamAgentScheduler._
import beam.sim.{BeamServices, HasServices}
import com.google.common.collect.TreeMultimap

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.FiniteDuration


object BeamAgentScheduler {

  sealed trait SchedulerMessage

  case object StartSchedule extends SchedulerMessage

  case object IllegalTriggerGoToError extends SchedulerMessage

  case class DoSimStep(tick: Double) extends SchedulerMessage

  case class CompletionNotice(id: Long, newTriggers: Vector[ScheduleTrigger] = Vector[ScheduleTrigger]()) extends SchedulerMessage

  case class ScheduleTrigger(trigger: Trigger, agent: ActorRef, priority: Int = 0) extends SchedulerMessage {
    def completed(triggerId: Long, scheduleTriggers: Vector[ScheduleTrigger]): CompletionNotice = {
      CompletionNotice(triggerId, scheduleTriggers)
    }

  }

  /**
    *
    * @param triggerWithId
    * @param agent recipient of this trigger
    * @param priority
    */
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

  def SchedulerProps(stopTick: Double = 3600.0 * 24.0, maxWindow: Double = 1.0): Props = {
    Props(classOf[BeamAgentScheduler], stopTick, maxWindow)
  }
}

class BeamAgentScheduler(val beamServices: BeamServices,  stopTick: Double, val maxWindow: Double) extends Actor with HasServices {
  val debugEnabled = beamServices.beamConfig.beam.debug.debugEnabled
  val log = Logging(context.system, this)
  var triggerQueue = new mutable.PriorityQueue[ScheduledTrigger]()
  var awaitingResponse: TreeMultimap[java.lang.Double, java.lang.Long] = TreeMultimap.create[java.lang.Double, java.lang.Long]()
  var awaitingResponseVerbose: TreeMultimap[java.lang.Double, ScheduledTrigger] = TreeMultimap.create[java.lang.Double, ScheduledTrigger]() //com.google.common.collect.Ordering.natural(), com.google.common.collect.Ordering.arbitrary())
  val triggerIdToTick: mutable.Map[Long, Double] = scala.collection.mutable.Map[Long, java.lang.Double]()
  val triggerIdToScheduledTrigger: mutable.Map[Long, ScheduledTrigger] = scala.collection.mutable.Map[Long, ScheduledTrigger]()
  private var idCount: Long = 0L
  var startSender: ActorRef = _
  private var nowInSeconds: Double = 0.0
  @volatile var isRunning = true

  private var previousTotalAwaitingRespone: AtomicLong = new AtomicLong(0)
  private var currentTotalAwaitingResponse: AtomicLong = new AtomicLong(0)
  private var numberRepeats: AtomicLong = new AtomicLong(0)

  def increment(): Unit = {
    previousTotalAwaitingRespone.incrementAndGet
  }


  override def postStop(): Unit = {
    monitorThread.foreach(_.cancel())
  }

  def scheduleTrigger(triggerToSchedule: ScheduleTrigger): Unit = {
    this.idCount += 1
    if (nowInSeconds - triggerToSchedule.trigger.tick > maxWindow) {
      if (debugEnabled) {
        log.warning(s"Cannot schedule an event $triggerToSchedule at tick ${triggerToSchedule.trigger.tick} when 'nowInSeconds' is at $nowInSeconds sender=${sender()} sending target agent to Error")
        triggerToSchedule.agent ! IllegalTriggerGoToError
      } else {
        throw new RuntimeException(s"Cannot schedule an event $triggerToSchedule at tick ${triggerToSchedule.trigger.tick} when 'nowInSeconds' is at $nowInSeconds sender=${sender()}")
      }
    } else {
      val triggerWithId = TriggerWithId(triggerToSchedule.trigger, this.idCount)
      triggerQueue.enqueue(ScheduledTrigger(triggerWithId, triggerToSchedule.agent, triggerToSchedule.priority))
      triggerIdToTick += (triggerWithId.triggerId -> triggerToSchedule.trigger.tick)
      //    log.info(s"recieved trigger to schedule $triggerToSchedule")
    }
  }

  def receive: Receive = {
    case StartSchedule =>
      log.info("starting scheduler")
      this.startSender = sender()
      self ! DoSimStep(0.0)

    case DoSimStep(newNow: Double) if newNow <= stopTick =>
      nowInSeconds = newNow
      if (nowInSeconds >= 13547) {
        val i = 0
      }
      if (awaitingResponse.isEmpty || nowInSeconds - awaitingResponse.keySet().first() + 1 < maxWindow) {
        while (triggerQueue.nonEmpty && triggerQueue.head.triggerWithId.trigger.tick <= nowInSeconds) {
          val scheduledTrigger = this.triggerQueue.dequeue
          val triggerWithId = scheduledTrigger.triggerWithId
          //log.info(s"dispatching $triggerWithId")
          awaitingResponse.put(triggerWithId.trigger.tick, triggerWithId.triggerId)
          if (debugEnabled) {
            awaitingResponseVerbose.put(triggerWithId.trigger.tick, scheduledTrigger)
            triggerIdToScheduledTrigger.put(triggerWithId.triggerId, scheduledTrigger)
          }
          scheduledTrigger.agent ! triggerWithId
        }
        if (nowInSeconds > 0 && nowInSeconds % 1800 == 0) {
          log.info("Hour " + nowInSeconds / 3600.0 + " completed.")
        }
        if (awaitingResponse.isEmpty || (nowInSeconds + 1) - awaitingResponse.keySet().first() + 1 < maxWindow) {
          self ! DoSimStep(nowInSeconds + 1.0)
        } else {
          Thread.sleep(10)
          self ! DoSimStep(nowInSeconds)
        }
      } else {
        Thread.sleep(10)
        self ! DoSimStep(nowInSeconds)
      }

    case DoSimStep(newNow: Double) if newNow > stopTick =>
      nowInSeconds = newNow
      if (awaitingResponse.isEmpty) {
        log.info(s"Stopping BeamAgentScheduler @ tick $nowInSeconds")
        startSender ! CompletionNotice(0L)
      } else {
        Thread.sleep(10)
        self ! DoSimStep(nowInSeconds)
      }

    case notice@CompletionNotice(triggerId: Long, newTriggers: Vector[ScheduleTrigger]) =>
      //      log.info(s"recieved notice that trigger triggerId: $triggerId is complete")
      newTriggers.foreach {
        scheduleTrigger
      }
      val completionTickOpt = triggerIdToTick.get(triggerId)
      if (completionTickOpt.isEmpty || !triggerIdToTick.contains(triggerId) || !awaitingResponse.containsKey(completionTickOpt.get)) {
        log.error(s"Received bad completion notice ${notice} from ${sender().path}")
      } else {
        awaitingResponse.remove(completionTickOpt.get, triggerId)
        if (debugEnabled) {
          awaitingResponseVerbose.remove(completionTickOpt.get, triggerIdToScheduledTrigger(triggerId))
          triggerIdToScheduledTrigger -= triggerId
        }
      }
      triggerIdToTick -= triggerId

    case triggerToSchedule: ScheduleTrigger =>
      scheduleTrigger(triggerToSchedule)

    case msg =>
      log.error(s"received unknown message: $msg")
  }

  val monitorThread = if (debugEnabled || beamServices.beamConfig.beam.debug.skipOverBadActors ) {
    Option(context.system.scheduler.schedule(new FiniteDuration(5, TimeUnit.SECONDS), new FiniteDuration(5, TimeUnit.SECONDS), new Runnable {
      override def run(): Unit = {
        try {
          if (beamServices.beamConfig.beam.debug.skipOverBadActors) {
            var numReps = 0L
            currentTotalAwaitingResponse.set(awaitingResponseVerbose.values().stream().count())
            if (currentTotalAwaitingResponse.get() == previousTotalAwaitingRespone.get() && currentTotalAwaitingResponse.get() != 0) {
              numReps = numberRepeats.incrementAndGet()
              log.error(s"DEBUG: $numReps repeats.")
            } else {
              numberRepeats.set(0)
            }
            if (numReps > 2) {
              log.error(s"DEBUG: $numReps > 2 repeats!!! Clearing out stuck agents and proceeding with schedule")
              awaitingResponseVerbose.values().stream().forEach({ x =>
                x.agent ! IllegalTriggerGoToError
                currentTotalAwaitingResponse.set(0)
                self ! CompletionNotice(x.triggerWithId.triggerId)
              })
            }
            previousTotalAwaitingRespone.set(currentTotalAwaitingResponse.get())
          }
          if(debugEnabled){
            log.error(s"\n\tnowInSeconds=$nowInSeconds,\n\tawaitingResponse.size=${awaitingResponse.size()},\n\ttriggerQueue.size=${triggerQueue.size},\n\ttriggerQueue.head=${triggerQueue.headOption}\n\tawaitingResponse.head=${awaitingToString}")
          }
        } catch {
          case e: Throwable =>
          //do nothing
        }
      }
    }))
  } else {
    None
  }

  def awaitingToString: String = {
    this.synchronized {
      if (awaitingResponse.keySet().isEmpty) {
        "empty"
      } else {
        if (debugEnabled) {
          awaitingResponse.synchronized(
            s"${awaitingResponseVerbose.get(awaitingResponseVerbose.keySet().first())}}"
          )
        } else {
          awaitingResponse.synchronized(
            awaitingResponseVerbose.synchronized(
              s"${awaitingResponse.keySet().first()} ${awaitingResponse.get(awaitingResponse.keySet().first())}}"
            )
          )
        }
      }
    }
  }
}


