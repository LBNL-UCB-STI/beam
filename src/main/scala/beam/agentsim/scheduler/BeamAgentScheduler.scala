package beam.agentsim.scheduler

import java.util.Comparator
import java.util.concurrent.TimeUnit
import akka.actor.{ActorLogging, ActorRef, BeamLoggingReceive, Cancellable, Props, Terminated}
import akka.event.LoggingReceive
import akka.util.Timeout
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.modalbehaviors.DrivesVehicle.EndRefuelSessionTrigger
import beam.agentsim.agents.ridehail.RideHailManager.{
  ContinueBufferedRideHailRequests,
  RecoverFromStuckness,
  RideHailRepositioningTrigger
}
import beam.agentsim.scheduler.BeamAgentScheduler._
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.sim.config.BeamConfig
import beam.utils.StuckFinder
import beam.utils.logging.{LogActorState, LoggingMessageActor}
import com.google.common.collect.TreeMultimap

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{Deadline, FiniteDuration}

case class RideHailingManagerIsExtremelySlowException(
  message: String,
  cause: Throwable = null
) extends Exception(message, cause)

object BeamAgentScheduler {

  sealed trait SchedulerMessage

  /**
    * Message to start (or restart) the scheduler at the start of each iteration
    *
    * @param iteration current iteration (to update internal state)
    */
  case class StartSchedule(iteration: Int) extends SchedulerMessage

  case class IllegalTriggerGoToError(reason: String) extends SchedulerMessage

  case class DoSimStep(tick: Int) extends SchedulerMessage

  case class CompletionNotice(
    triggerId: Long,
    newTriggers: Seq[ScheduleTrigger] = Vector[ScheduleTrigger]()
  ) extends SchedulerMessage
      with HasTriggerId

  case object Monitor extends SchedulerMessage

  case object RequestCurrentTime extends SchedulerMessage

  case object SkipOverBadActors extends SchedulerMessage

  case class ScheduleTrigger(trigger: Trigger, agent: ActorRef, priority: Int = 0) extends SchedulerMessage

  case class ScheduleKillTrigger(agent: ActorRef, triggerId: Long) extends SchedulerMessage with HasTriggerId

  case class KillTrigger(tick: Int) extends Trigger

  case class RideHailManagerStuckDetectionLog(tick: Option[Int], alreadyLogged: Boolean)

  case class MonitorStuckDetectionState(
    tick: Int,
    awaitingReponseSize: Int,
    triggerQueueSize: Int,
    triggerQueueHead: Option[ScheduledTrigger]
  )

  /**
    *
    * @param triggerWithId identifier
    * @param agent         recipient of this trigger
    * @param priority      schedule priority
    */
  case class ScheduledTrigger(triggerWithId: TriggerWithId, agent: ActorRef, priority: Int)
      extends Ordered[ScheduledTrigger] {

    // Compare is on 3 levels with higher priority (i.e. front of the queue) for:
    //   smaller tick => then higher priority value => then lower triggerId
    def compare(that: ScheduledTrigger): Int =
      java.lang.Double.compare(that.triggerWithId.trigger.tick, triggerWithId.trigger.tick) match {
        case 0 =>
          java.lang.Integer.compare(priority, that.priority) match {
            case 0 =>
              java.lang.Long
                .compare(that.triggerWithId.triggerId, triggerWithId.triggerId)
            case c => c
          }
        case c => c
      }
  }

  def SchedulerProps(
    beamConfig: BeamConfig,
    stopTick: Int = TimeUnit.HOURS.toSeconds(24).toInt,
    maxWindow: Int = 1,
    stuckFinder: StuckFinder
  ): Props = {
    Props(classOf[BeamAgentScheduler], beamConfig, stopTick, maxWindow, stuckFinder)
  }

  object ScheduledTriggerComparator extends Comparator[ScheduledTrigger] {

    def compare(st1: ScheduledTrigger, st2: ScheduledTrigger): Int =
      java.lang.Double
        .compare(st1.triggerWithId.trigger.tick, st2.triggerWithId.trigger.tick) match {
        case 0 =>
          java.lang.Integer.compare(st2.priority, st1.priority) match {
            case 0 =>
              java.lang.Long.compare(st1.triggerWithId.triggerId, st2.triggerWithId.triggerId)
            case c => c
          }
        case c => c
      }
  }

}

case object BeamAgentSchedulerTimer

class BeamAgentScheduler(
  val beamConfig: BeamConfig,
  stopTick: Int,
  val maxWindow: Int,
  val stuckFinder: StuckFinder
) extends LoggingMessageActor
    with ActorLogging {
  // Used to set a limit on the total time to process messages (we want this to be quite large).
  private implicit val timeout: Timeout = Timeout(50000, TimeUnit.SECONDS)

  private var started = false

  private val triggerQueue =
    new java.util.PriorityQueue[ScheduledTrigger](ScheduledTriggerComparator)
  private val awaitingResponse: TreeMultimap[java.lang.Integer, ScheduledTrigger] = TreeMultimap
    .create[java.lang.Integer, ScheduledTrigger]() //com.google.common.collect.Ordering.natural(), com.google.common.collect.Ordering.arbitrary())
  private val triggerIdToTick: mutable.Map[Long, Integer] =
    scala.collection.mutable.Map[Long, java.lang.Integer]()
  private val triggerIdToScheduledTrigger: mutable.Map[Long, ScheduledTrigger] =
    scala.collection.mutable.Map[Long, ScheduledTrigger]()

  private var idCount: Long = 0L
  private var startSender: ActorRef = _
  private var nowInSeconds: Int = 0

  private val maybeTriggerMeasurer: Option[TriggerMeasurer] = if (beamConfig.beam.debug.triggerMeasurer.enabled) {
    Some(new TriggerMeasurer(beamConfig.beam.debug.triggerMeasurer))
  } else {
    None
  }

  private var rideHailManagerStuckDetectionLog = RideHailManagerStuckDetectionLog(None, false)

  private var monitorStuckDetectionState: Option[MonitorStuckDetectionState] = None

  private var startedAt: Deadline = _
  // Event stream state and cleanup management
  private var currentIter: Int = -1

  private val scheduledTriggerToStuckTimes: mutable.HashMap[ScheduledTrigger, Int] =
    mutable.HashMap.empty

  private var monitorTask: Option[Cancellable] = None
  private var stuckAgentChecker: Option[Cancellable] = None

  private val initialDelay = beamConfig.beam.agentsim.scheduleMonitorTask.initialDelay
  private val interval = beamConfig.beam.agentsim.scheduleMonitorTask.interval

  def scheduleTrigger(triggerToSchedule: ScheduleTrigger): Unit = {
    this.idCount += 1

    if (nowInSeconds - triggerToSchedule.trigger.tick > maxWindow) {
      triggerToSchedule.agent ! IllegalTriggerGoToError(
        s"Cannot schedule an event $triggerToSchedule at tick ${triggerToSchedule.trigger.tick} when 'nowInSeconds' is at $nowInSeconds}"
      )
    } else {
      val triggerWithId = TriggerWithId(triggerToSchedule.trigger, this.idCount)
      triggerQueue.add(
        ScheduledTrigger(triggerWithId, triggerToSchedule.agent, triggerToSchedule.priority)
      )
      triggerIdToTick += (triggerWithId.triggerId -> triggerToSchedule.trigger.tick)
      //    log.info(s"recieved trigger to schedule $triggerToSchedule")
    }
  }

  override def aroundPostStop(): Unit = {
    log.info("aroundPostStop. Stopping all scheduled tasks...")
    stuckAgentChecker.foreach(_.cancel())
    monitorTask.foreach(_.cancel())
    super.aroundPostStop()
  }

  def loggedReceive: Receive = BeamLoggingReceive {
    case StartSchedule(it) =>
      log.info(s"starting scheduler at iteration $it")
      this.startSender = sender()
      this.currentIter = it
      started = true
      startedAt = Deadline.now
      stuckAgentChecker = scheduleStuckAgentCheck
      monitorTask = scheduleMonitorTask
      doSimStep(0)

    case DoSimStep(newNow: Int) =>
      doSimStep(newNow)

    case notice @ CompletionNotice(triggerId: Long, newTriggers: Seq[ScheduleTrigger]) =>
      // if (!newTriggers.filter(x=>x.agent.path.toString.contains("RideHailManager")).isEmpty){
      // DebugLib.emptyFunctionForSettingBreakPoint()
      // }

      newTriggers.foreach {
        scheduleTrigger
      }
      val completionTickOpt = triggerIdToTick.get(triggerId)
      if (completionTickOpt.isEmpty || !triggerIdToTick
            .contains(triggerId) || !awaitingResponse
            .containsKey(completionTickOpt.get)) {
        log.error(s"Received bad completion notice $notice from ${sender().path}")
      } else {
        val trigger = triggerIdToScheduledTrigger(triggerId)
        awaitingResponse.remove(completionTickOpt.get, trigger)
        val st = triggerIdToScheduledTrigger(triggerId)
        awaitingResponse.remove(completionTickOpt.get, st)
        stuckFinder.removeByKey(st)
        triggerIdToScheduledTrigger -= triggerId
        maybeTriggerMeasurer.foreach(_.resolved(trigger.triggerWithId))
      }
      triggerIdToTick -= triggerId
      if (started) doSimStep(nowInSeconds)

    case triggerToSchedule: ScheduleTrigger =>
      context.watch(triggerToSchedule.agent)
      scheduleTrigger(triggerToSchedule)
      if (started) doSimStep(nowInSeconds)

    case ScheduleKillTrigger(agent: ActorRef, _) =>
      context.watch(agent)
      scheduleTrigger(ScheduleTrigger(KillTrigger(nowInSeconds + maxWindow), agent))

    case Terminated(actor) =>
      terminateActor(actor)

    case Monitor =>
      if (beamConfig.beam.debug.debugEnabled) {
        val logStr =
          s"""
             |\tnowInSeconds=$nowInSeconds
             |\tawaitingResponse.size=${awaitingResponse.size()}
             |\ttriggerQueue.size=${triggerQueue.size}
             |\ttriggerQueue.head=${Option(triggerQueue.peek())}
             |\tawaitingResponse.head=$awaitingToString""".stripMargin
        log.info(logStr)

        // if RidehailManager at first position in queue, it is very likely, that we are stuck
        awaitingResponse.values().asScala.take(1).foreach { x =>
          if (x.agent.path.name.contains("RideHailManager")) {
            rideHailManagerStuckDetectionLog match {
              case RideHailManagerStuckDetectionLog(Some(tick), true) if tick == nowInSeconds  => // still stuck, no need to print state again
              case RideHailManagerStuckDetectionLog(Some(tick), false) if tick == nowInSeconds =>
                // the time has not changed since set last monitor timeout and RidehailManager still blocking scheduler -> log state and try to remove stuckness
                rideHailManagerStuckDetectionLog = RideHailManagerStuckDetectionLog(Some(nowInSeconds), true)
                x.agent ! LogActorState
//                x.agent ! RecoverFromStuckness(x.triggerWithId.trigger.tick)
              case _ =>
                // register tick (to see, if it changes till next monitor timeout).
                rideHailManagerStuckDetectionLog = RideHailManagerStuckDetectionLog(Some(nowInSeconds), false)
            }
          } else {
            // Send to the stuck agent in order to spy it's internal state in debugger
            x.agent ! BeamAgentSchedulerTimer
//            monitorStuckDetectionState match {
//              case Some(MonitorStuckDetectionState(tick, awaitingReponseSize, triggerQueueSize, Some(triggerQueueHead)))
//                  if ((tick == nowInSeconds && awaitingReponseSize == awaitingResponse
//                    .size()) && (triggerQueueSize == triggerQueue.size() && triggerQueueHead == triggerQueue.peek())) =>
//                log.info("monitorStuckDetection removing agent: " + x.agent.path)
//                terminateActor(x.agent)
//
//              case _ =>
//            }
          }
        }

        monitorStuckDetectionState = Some(
          MonitorStuckDetectionState(
            nowInSeconds,
            awaitingResponse.size(),
            triggerQueue.size,
            Some(triggerQueue.peek())
          )
        )

        awaitingResponse.values().asScala.take(10).foreach(x => log.info("awaitingResponse:" + x.toString))

      }

    case SkipOverBadActors =>
      val stuckAgents = stuckFinder.detectStuckAgents()
      if (stuckAgents.nonEmpty) {
        log.warning("{} agents are candidates to be cleaned", stuckAgents.size)

        val canClean = stuckAgents.filterNot { stuckInfo =>
          val st = stuckInfo.value
          st.agent.path.name.contains("RideHailManager") && st.triggerWithId.trigger
            .isInstanceOf[RideHailRepositioningTrigger]
        }
        log.warning("Cleaning {} agents", canClean.size)
        canClean.foreach { stuckInfo =>
          val st = stuckInfo.value
          st.agent ! IllegalTriggerGoToError("Stuck Agent")
          self ! CompletionNotice(st.triggerWithId.triggerId)
          log.warning("Cleaned {}", st)
        }

        val unexpectedStuckAgents = stuckAgents.diff(canClean)
        log.warning("Processing {} unexpected agents", unexpectedStuckAgents.size)
        unexpectedStuckAgents.foreach { stuckInfo =>
          val st = stuckInfo.value
          val times = scheduledTriggerToStuckTimes.getOrElse(st, 0)
          scheduledTriggerToStuckTimes.put(st, times + 1)
          // We have to add them back to `stuckFinder`
          if (times < 50) {
            stuckFinder.add(stuckInfo.time, st, false)
          }

          if (times == 10) {
            log.error("RideHailingManager is slow")
          } else if (times == 50) {
            throw RideHailingManagerIsExtremelySlowException(
              "RideHailingManager is extremely slow"
            )
          }
        }
      }
      if (started) doSimStep(nowInSeconds)
  }

  private def terminateActor(actor: ActorRef): Unit = {
    awaitingResponse
      .values()
      .stream()
      .filter(trigger => trigger.agent == actor)
      .forEach(trigger => {
        // We do not need to remove it from `awaitingResponse` or `stuckFunder`.
        // We will do it a bit later when `CompletionNotice` will be received
        self ! CompletionNotice(trigger.triggerWithId.triggerId, Nil)
        log.error("Clearing trigger because agent died: " + trigger)
      })
  }

  @tailrec
  private def doSimStep(newNow: Int): Unit = {
    if (newNow <= stopTick || !triggerQueue.isEmpty && triggerQueue
          .peek()
          .triggerWithId
          .trigger
          .tick <= stopTick) {
      nowInSeconds = newNow
      if (awaitingResponse.isEmpty || nowInSeconds - awaitingResponse
            .keySet()
            .first() + 1 < maxWindow) {
        while (!triggerQueue.isEmpty && triggerQueue
                 .peek()
                 .triggerWithId
                 .trigger
                 .tick <= nowInSeconds) {
          val scheduledTrigger = this.triggerQueue.poll()
          val triggerWithId = scheduledTrigger.triggerWithId
          awaitingResponse.put(triggerWithId.trigger.tick, scheduledTrigger)
          stuckFinder.add(System.currentTimeMillis(), scheduledTrigger, true)

          triggerIdToScheduledTrigger.put(triggerWithId.triggerId, scheduledTrigger)
          maybeTriggerMeasurer.foreach(_.sent(triggerWithId, scheduledTrigger.agent))
          scheduledTrigger.agent ! triggerWithId
        }
        if (awaitingResponse.isEmpty || (nowInSeconds + 1) - awaitingResponse
              .keySet()
              .first() + 1 < maxWindow) {
          if (nowInSeconds > 0 && nowInSeconds % 1800 == 0) {
            log.info(
              "Hour " + nowInSeconds / 3600.0 + " completed. " + math.round(
                10 * (Runtime.getRuntime.totalMemory() - Runtime.getRuntime
                  .freeMemory()) / Math
                  .pow(1000, 3)
              ) / 10.0 + "(GB)"
            )
          }
          doSimStep(nowInSeconds + 1)
        }
      }

    } else {
      nowInSeconds = newNow
      if (awaitingResponse.isEmpty) {
        val duration = Deadline.now - startedAt
        stuckAgentChecker.foreach(_.cancel)
        log.info(
          s"Stopping BeamAgentScheduler @ tick $nowInSeconds. Iteration $currentIter executed in ${duration.toSeconds} seconds"
        )
        maybeTriggerMeasurer.foreach { triggerMeasurer =>
          if (beamConfig.beam.outputs.displayPerformanceTimings) {
            log.info(s"Statistics about trigger: ${System.lineSeparator()} ${triggerMeasurer.getStat}")
          }
          log.debug(s"Statistics about trigger: ${System.lineSeparator()} ${triggerMeasurer.getStat}")

          if (beamConfig.beam.debug.triggerMeasurer.writeStuckAgentDetectionConfig) {
            val jsonConf = triggerMeasurer.asStuckAgentDetectionConfig
            log.info(
              "Auto-generated stuck agent detection config (might need to tune it manually, especially `markAsStuckAfterMs`):"
            )
            val finalStr = System.lineSeparator() + jsonConf + System.lineSeparator()
            log.info(finalStr)
          }
        }

        // In BeamMobsim all rideHailAgents receive a 'Finish' message. If we also send a message from here to rideHailAgent, dead letter is reported, as at the time the second
        // Finish is sent to rideHailAgent, it is already stopped.
        triggerQueue.asScala.foreach(
          scheduledTrigger =>
            if (!scheduledTrigger.agent.path.toString.contains("rideHailAgent"))
              scheduledTrigger.agent ! Finish
        )

        startSender ! CompletionNotice(0L)
      }

    }
  }

  override def postStop(): Unit = {
    monitorTask.foreach(_.cancel())
    stuckAgentChecker.foreach(_.cancel())
  }

  def awaitingToString: String = {
    if (awaitingResponse.keySet().isEmpty) {
      "empty"
    } else {
      s"${awaitingResponse.get(awaitingResponse.keySet().first()).asScala.take(3)}"
    }
  }

  def scheduleMonitorTask: Option[Cancellable] = {
    if (beamConfig.beam.debug.debugEnabled)
      Some(
        context.system.scheduler.scheduleWithFixedDelay(
          new FiniteDuration(initialDelay, TimeUnit.SECONDS),
          new FiniteDuration(interval, TimeUnit.SECONDS),
          self,
          Monitor
        )
      )
    else None
  }

  def scheduleStuckAgentCheck: Option[Cancellable] = {
    if (beamConfig.beam.debug.stuckAgentDetection.enabled)
      Some(
        context.system.scheduler.scheduleWithFixedDelay(
          new FiniteDuration(
            beamConfig.beam.debug.stuckAgentDetection.checkIntervalMs,
            TimeUnit.MILLISECONDS
          ),
          new FiniteDuration(
            beamConfig.beam.debug.stuckAgentDetection.checkIntervalMs,
            TimeUnit.MILLISECONDS
          ),
          self,
          SkipOverBadActors
        )
      )
    else None
  }
}
