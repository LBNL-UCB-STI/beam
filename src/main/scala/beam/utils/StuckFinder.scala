package beam.utils

import java.util.concurrent.TimeUnit
import java.util.{Comparator, PriorityQueue}

import beam.agentsim.scheduler.BeamAgentScheduler.ScheduledTrigger
import beam.sim.config.BeamConfig
import com.typesafe.scalalogging.LazyLogging

import scala.annotation.tailrec
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.FiniteDuration


class StuckFinder(val beamConfig: BeamConfig) extends LazyLogging {
  private val stuckAgentDuration: FiniteDuration = FiniteDuration(beamConfig.beam.debug.secondsToWaitForSkip, TimeUnit.SECONDS)

  private val helper = new StuckFinderHelper[ScheduledTrigger]

  def add(ts: Long, st: ScheduledTrigger): Unit = {
    helper.add(ts, st)
  }

  def removeOldest: Option[ValueWithTs[ScheduledTrigger]] = {
    helper.removeOldest
  }

  def removeByKey(st: ScheduledTrigger): Option[ValueWithTs[ScheduledTrigger]] = {
    helper.removeByKey(st)
  }

  def detectStuckAgents: Seq[ScheduledTrigger] = {
    @tailrec
    def detectStuckAgents0(stuckAgents: mutable.ArrayBuffer[ScheduledTrigger]): Seq[ScheduledTrigger] = {
      removeOldest match {
        case Some(oldest) =>
          val isStuck: Boolean = isStuckAgent(oldest.value, oldest.ts, System.currentTimeMillis())
          if (!isStuck) {
            // We have to add it back
            add(oldest.ts, oldest.value)
            stuckAgents
          }
          else {
            stuckAgents += oldest.value
            detectStuckAgents0(stuckAgents)
          }
        case None =>
          stuckAgents
      }
    }
    detectStuckAgents0(ArrayBuffer.empty[ScheduledTrigger])
  }

  private def isStuckAgent(st: ScheduledTrigger, startedAtMs: Long, currentTimeMs: Long): Boolean = {
    val diff = currentTimeMs - startedAtMs
    val isStuck = diff > stuckAgentDuration.toMillis
    if (isStuck) {
      logger.warn(s"$st is stuck. Diff: $diff ms")
    }
    isStuck
  }
}
