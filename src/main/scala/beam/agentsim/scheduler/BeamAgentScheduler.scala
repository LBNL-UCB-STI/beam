package beam.agentsim.scheduler

import java.lang.Double
import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Terminated}
import akka.event.LoggingReceive
import akka.util.Timeout
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.rideHail.RideHailManager.RideHailAllocationManagerTimeout
import beam.agentsim.events.EventsSubscriber._
import beam.agentsim.scheduler.BeamAgentScheduler._
import beam.sim.config.BeamConfig
import com.google.common.collect.TreeMultimap

import scala.annotation.tailrec
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.FiniteDuration


object BeamAgentScheduler {

  sealed trait SchedulerMessage

  /**
    * Message to start (or restart) the scheduler at the start of each iteration
    *
    * @param iteration current iteration (to update internal state)
    */
  case class StartSchedule(iteration: Int) extends SchedulerMessage

  case class IllegalTriggerGoToError(reason: String) extends SchedulerMessage

  case class DoSimStep(tick: Double) extends SchedulerMessage

  case class CompletionNotice(id: Long, newTriggers: Seq[ScheduleTrigger] = Vector[ScheduleTrigger]()) extends SchedulerMessage

  case object Monitor extends SchedulerMessage

  case object SkipOverBadActors extends SchedulerMessage

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

  def SchedulerProps(beamConfig: BeamConfig, stopTick: Double = 3600.0 * 24.0, maxWindow: Double = 1.0): Props = {
    Props(classOf[BeamAgentScheduler], beamConfig, stopTick, maxWindow)
  }
}

class BeamAgentScheduler(val beamConfig: BeamConfig, stopTick: Double, val maxWindow: Double) extends Actor with ActorLogging {
  // Used to set a limit on the total time to process messages (we want this to be quite large).
  private implicit val timeout = Timeout(50000, TimeUnit.SECONDS)

  private var started = false

  private var triggerQueue: mutable.PriorityQueue[ScheduledTrigger] = new mutable.PriorityQueue[ScheduledTrigger]()
  private var awaitingResponse: TreeMultimap[java.lang.Double, ScheduledTrigger] = TreeMultimap.create[java.lang.Double, ScheduledTrigger]() //com.google.common.collect.Ordering.natural(), com.google.common.collect.Ordering.arbitrary())
  private val triggerIdToTick: mutable.Map[Long, Double] = scala.collection.mutable.Map[Long, java.lang.Double]()
  private val triggerIdToScheduledTrigger: mutable.Map[Long, ScheduledTrigger] = scala.collection.mutable.Map[Long, ScheduledTrigger]()

  private var idCount: Long = 0L
  private var startSender: ActorRef = _
  private var nowInSeconds: Double = 0.0

  private var previousTotalAwaitingRespone = 0L
  private var currentTotalAwaitingResponse = 0L
  private var numberRepeats = 0

  // Event stream state and cleanup management
  private var currentIter: Int = -1
  private val eventSubscriberRef = context.system.actorSelection(context.system./(SUBSCRIBER_NAME))

  private val monitorTask = if (beamConfig.beam.debug.debugEnabled) Some(context.system.scheduler.schedule(new FiniteDuration(1, TimeUnit.SECONDS), new FiniteDuration(3, TimeUnit.SECONDS), self, Monitor)) else None
  private val skipOverBadActorsTask = if (beamConfig.beam.debug.skipOverBadActors) Some(context.system.scheduler.schedule(new FiniteDuration(beamConfig.beam.debug.secondsToWaitForSkip * 2, TimeUnit.SECONDS), new FiniteDuration(math.round(beamConfig.beam.debug.secondsToWaitForSkip / 4.0), TimeUnit.SECONDS), self, SkipOverBadActors)) else None

  def increment(): Unit = {
    previousTotalAwaitingRespone += 1
  }

  def scheduleTrigger(triggerToSchedule: ScheduleTrigger): Unit = {
    this.idCount += 1

    if (nowInSeconds - triggerToSchedule.trigger.tick > maxWindow) {
      triggerToSchedule.agent ! IllegalTriggerGoToError(s"Cannot schedule an event $triggerToSchedule at tick ${triggerToSchedule.trigger.tick} when 'nowInSeconds' is at $nowInSeconds}")
    } else {
      val triggerWithId = TriggerWithId(triggerToSchedule.trigger, this.idCount)
      triggerQueue.enqueue(ScheduledTrigger(triggerWithId, triggerToSchedule.agent, triggerToSchedule.priority))
      triggerIdToTick += (triggerWithId.triggerId -> triggerToSchedule.trigger.tick)
      //    log.info(s"recieved trigger to schedule $triggerToSchedule")
    }
  }

  def receive: Receive = LoggingReceive {
    case StartSchedule(it) =>
      log.info(s"starting scheduler at iteration $it")
      this.startSender = sender()
      this.currentIter = it
      started = true
      doSimStep(0.0)

    case DoSimStep(newNow: Double) =>
      doSimStep(newNow)

    case notice@CompletionNotice(triggerId: Long, newTriggers: Seq[ScheduleTrigger]) =>
    // if (!newTriggers.filter(x=>x.agent.path.toString.contains("RideHailManager")).isEmpty){
      // DebugLib.emptyFunctionForSettingBreakPoint()
      // }

      newTriggers.foreach {
        scheduleTrigger
      }
      val completionTickOpt = triggerIdToTick.get(triggerId)
      if (completionTickOpt.isEmpty || !triggerIdToTick.contains(triggerId) || !awaitingResponse.containsKey(completionTickOpt.get)) {
        log.error(s"Received bad completion notice $notice from ${sender().path}")
      } else {
        awaitingResponse.remove(completionTickOpt.get, triggerIdToScheduledTrigger(triggerId))
        triggerIdToScheduledTrigger -= triggerId
      }
      triggerIdToTick -= triggerId
      if (started) doSimStep(nowInSeconds)

    case triggerToSchedule: ScheduleTrigger =>
      context.watch(triggerToSchedule.agent)
      scheduleTrigger(triggerToSchedule)
      if (started) doSimStep(nowInSeconds)

    case Terminated(actor) =>
      awaitingResponse.values().stream()
        .filter(trigger => trigger.agent == actor)
        .forEach(trigger => {
          self ! CompletionNotice(trigger.triggerWithId.triggerId, Nil)
          log.error("Clearing trigger because agent died: " + trigger)
        })

    case Monitor =>
      log.debug(s"\n\tnowInSeconds=$nowInSeconds,\n\tawaitingResponse.size=${awaitingResponse.size()},\n\ttriggerQueue.size=${triggerQueue.size},\n\ttriggerQueue.head=${triggerQueue.headOption}\n\tawaitingResponse.head=$awaitingToString")
      awaitingResponse.values().forEach(x => log.debug("awaitingResponse:" + x.toString))

    case SkipOverBadActors =>
      var numReps = 0L
      currentTotalAwaitingResponse = awaitingResponse.values().stream().count()
      if (currentTotalAwaitingResponse == previousTotalAwaitingRespone && currentTotalAwaitingResponse != 0) {
        numberRepeats += 1
        numReps = numberRepeats
        log.debug("DEBUG: {} repeats.", numReps )
      } else {
        numberRepeats = 0
      }

      if (numReps > 4) {
        // When debugging, use evaulate expression and this to see first stuck agent: awaitingResponse.asMap().firstEntry().getValue.iterator().next().agent.actorCell._actor
        var numAgentsClearedOut=0
        awaitingResponse.get(awaitingResponse.keySet().first()).forEach({ x =>
          // the check makes sure that slow RideHailManager (repositioning) does not cause kill the rideHailManager actor
          // however the numReps>50 ensures that we eventually
          if (x.agent.path.name.contains("RideHailingManager") && x.triggerWithId.trigger.isInstanceOf[RideHailAllocationManagerTimeout]) {
            if (numReps==10){
              log.error("RideHailingManager is slow")
            } else if (numReps==50){
              throw new RuntimeException("RideHailingManager is extremly slow")
            }
          } else {
            x.agent ! IllegalTriggerGoToError("Stuck Agent")
            currentTotalAwaitingResponse = 0
            self ! CompletionNotice(x.triggerWithId.triggerId)
            numAgentsClearedOut+=numAgentsClearedOut
            log.error("clearing agent: " + x)
          }
        })

        if (numAgentsClearedOut>0){
          val reason = s"Cleared out $numAgentsClearedOut stuck agents and proceeding with schedule"
          log.error(reason)
        }
      }
      previousTotalAwaitingRespone = currentTotalAwaitingResponse
      if (started) doSimStep(nowInSeconds)
  }

  @tailrec
  private def doSimStep(newNow: Double): Unit = {
    if (newNow <= stopTick) {
      nowInSeconds = newNow

      // println("doSimStep:" + newNow)

      if (awaitingResponse.isEmpty || nowInSeconds - awaitingResponse.keySet().first() + 1 < maxWindow) {
        while (triggerQueue.nonEmpty && triggerQueue.head.triggerWithId.trigger.tick <= nowInSeconds) {
          val scheduledTrigger = this.triggerQueue.dequeue
          val triggerWithId = scheduledTrigger.triggerWithId
          //log.info(s"dispatching $triggerWithId")
          awaitingResponse.put(triggerWithId.trigger.tick, scheduledTrigger)
          triggerIdToScheduledTrigger.put(triggerWithId.triggerId, scheduledTrigger)
          scheduledTrigger.agent ! triggerWithId
        }
        if (awaitingResponse.isEmpty || (nowInSeconds + 1) - awaitingResponse.keySet().first() + 1 < maxWindow) {
          if (nowInSeconds > 0 && nowInSeconds % 1800 == 0) {
            log.info("Hour " + nowInSeconds / 3600.0 + " completed. " + math.round(10 * (Runtime.getRuntime.totalMemory() - Runtime.getRuntime.freeMemory()) / Math.pow(1000, 3)) / 10.0 + "(GB)")
          }
          doSimStep(nowInSeconds + 1.0)
        }
      }

    } else {
      nowInSeconds = newNow
      if (awaitingResponse.isEmpty) {
        log.info(s"Stopping BeamAgentScheduler @ tick $nowInSeconds")

        // In BeamMobsim all rideHailAgents receive a 'Finish' message. If we also send a message from here to rideHailAgent, dead letter is reported, as at the time the second
        // Finish is sent to rideHailAgent, it is already stopped.
        triggerQueue.dequeueAll.foreach(scheduledTrigger => if (!scheduledTrigger.agent.path.toString.contains("rideHailAgent")) scheduledTrigger.agent ! Finish)

        startSender ! CompletionNotice(0L)
      }

    }
  }

  override def postStop(): Unit = {
    monitorTask.foreach(_.cancel())
    skipOverBadActorsTask.foreach(_.cancel())
  }

  def awaitingToString: String = {
    if (awaitingResponse.keySet().isEmpty) {
      "empty"
    } else {
      s"${awaitingResponse.get(awaitingResponse.keySet().first())}"
    }
  }

}


