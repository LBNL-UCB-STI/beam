package beam.utils

import beam.agentsim.scheduler.BeamAgentScheduler.ScheduledTrigger
import beam.sim.config.BeamConfig.Beam.Debug.StuckAgentDetection
import com.typesafe.scalalogging.LazyLogging

import scala.annotation.tailrec
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

/** THIS CLASS IS NOT THREAD-SAFE!!! It's safe to use it inside actor, but not use the reference in Future and other threads..
  */
class StuckFinder(val stuckAgentDetectionCfg: StuckAgentDetection) extends LazyLogging {
  private val helper = new StuckFinderHelper[ScheduledTrigger]


  def add(time: Long, st: ScheduledTrigger): Unit = {
    helper.add(time, st)
  }

  def removeOldest: Option[ValueWithTime[ScheduledTrigger]] = {
    helper.removeOldest
  }

  def removeByKey(st: ScheduledTrigger): Option[ValueWithTime[ScheduledTrigger]] = {
    helper.removeByKey(st)
  }

  def detectStuckAgents(time: Long = System.currentTimeMillis()): Seq[ValueWithTime[ScheduledTrigger]] = {
    @tailrec
    def detectStuckAgents0(stuckAgents: mutable.ArrayBuffer[ValueWithTime[ScheduledTrigger]]): Seq[ValueWithTime[ScheduledTrigger]] = {
      removeOldest match {
        case Some(oldest) =>
          val isStuck: Boolean = isStuckAgent(oldest.value, oldest.time, time)
          if (!isStuck) {
            // We have to add it back
            add(oldest.time, oldest.value)
            stuckAgents
          }
          else {
            stuckAgents += oldest
            detectStuckAgents0(stuckAgents)
          }
        case None =>
          stuckAgents
      }
    }
    detectStuckAgents0(ArrayBuffer.empty[ValueWithTime[ScheduledTrigger]])
  }

  private def isStuckAgent(st: ScheduledTrigger, startedAtMs: Long, currentTimeMs: Long): Boolean = {
    val diff = currentTimeMs - startedAtMs
    val isStuck = diff > stuckAgentDetectionCfg.markAsStuckAfterMs
    if (isStuck) {
      logger.warn(s"$st is stuck. Diff: $diff ms")
    }
    isStuck
  }
}
