package beam.utils

import beam.agentsim.scheduler.BeamAgentScheduler.ScheduledTrigger
import beam.sim.config.BeamConfig.Beam.Debug.StuckAgentDetection
import com.typesafe.scalalogging.LazyLogging

import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer

/** THIS CLASS IS NOT THREAD-SAFE!!! It's safe to use it inside actor, but not use the reference in Future and other threads..
  */
class StuckFinder(val cfg: StuckAgentDetection) extends LazyLogging {
  verifyTypesExist()

  private val class2Helper: Map[Class[_], StuckFinderHelper[ScheduledTrigger]] = cfg.thresholds.map { option =>
    val clazz = Class.forName(option.triggerType)
    (clazz, new StuckFinderHelper[ScheduledTrigger])
  }.toMap

  private val class2Threshold: Map[Class[_], Long] = cfg.thresholds.map { option =>
    val clazz = Class.forName(option.triggerType)
    logger.info("{} => {} ms", clazz, option.markAsStuckAfterMs)
    (clazz, option.markAsStuckAfterMs)
  }.toMap

  def add(time: Long, st: ScheduledTrigger): Unit = {
    class2Helper(toKey(st)).add(time, st)
  }

  def removeByKey(st: ScheduledTrigger): Option[ValueWithTime[ScheduledTrigger]] = {
    class2Helper(toKey(st)).removeByKey(st)
  }

  def detectStuckAgents(
    time: Long = System.currentTimeMillis()
  ): Seq[ValueWithTime[ScheduledTrigger]] = {
    @tailrec
    def detectStuckAgents0(helper: StuckFinderHelper[ScheduledTrigger],
                           stuckAgents: ArrayBuffer[ValueWithTime[ScheduledTrigger]]): Seq[ValueWithTime[ScheduledTrigger]] = {
      helper.removeOldest match {
        case Some(oldest) =>
          val isStuck: Boolean = isStuckAgent(oldest.value, oldest.time, time)
          if (!isStuck) {
            // We have to add it back
            add(oldest.time, oldest.value)
            stuckAgents
          } else {
            stuckAgents += oldest
            detectStuckAgents0(helper, stuckAgents)
          }
        case None =>
          stuckAgents
      }
    }

    val result = ArrayBuffer.empty[ValueWithTime[ScheduledTrigger]]
    class2Helper.values.foreach { helper =>
      detectStuckAgents0(helper, result)
    }
    result
  }

  def isStuckAgent(st: ScheduledTrigger, startedAtMs: Long, currentTimeMs: Long): Boolean = {
    val diff = currentTimeMs - startedAtMs
    val threshold = class2Threshold(toKey(st))
    val isStuck = diff > threshold
    if (isStuck) {
      logger.warn(s"$st is stuck. Diff: $diff ms, Threshold: $threshold ms")
    }
    isStuck
  }

  private def toKey(st: ScheduledTrigger): Class[_] = st.triggerWithId.trigger.getClass

  private def verifyTypesExist(): Unit = {
    // Make sure that those classes exist
    cfg.thresholds.foreach { t =>
      // ClassNotFoundException  will be thrown if class does not exists or renamed or deleted
      Class.forName(t.triggerType)
    }
  }
}
