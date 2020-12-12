package beam.utils

import akka.actor.ActorRef
import beam.agentsim.scheduler.BeamAgentScheduler.ScheduledTrigger
import beam.agentsim.scheduler.Trigger
import beam.sim.config.BeamConfig.Beam.Debug.StuckAgentDetection
import beam.sim.config.BeamConfig.Beam.Debug.StuckAgentDetection.Thresholds$Elm
import beam.utils.logging.ExponentialLazyLogging
import beam.utils.reflection.ReflectionUtils
import com.typesafe.scalalogging.LazyLogging

import scala.annotation.tailrec
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

/** THIS CLASS IS NOT THREAD-SAFE!!! It's safe to use it inside actor, but not use the reference in Future and other threads..
  */
class StuckFinder(val cfg: StuckAgentDetection) extends LazyLogging {
  private var tickValue: Int = -1
  private var lastUpdatedTime: Long = 0
  private var numCriticalStuckMessages = 0

  private val actorToTriggerMessages: mutable.Map[ActorRef, mutable.Map[Class[_], Int]] =
    mutable.Map[ActorRef, mutable.Map[Class[_], Int]]()

  if (!cfg.enabled) {
    logger.info("StuckFinder is ** DISABLED **")
  } else {
    logger.info("StuckFinder is ** ENABLED **")
    verifyTypesExist()

  }
  private val triggerTypeToActorThreshold: Map[Class[_], Map[String, Int]] = if (!cfg.checkMaxNumberOfMessagesEnabled) {
    Map.empty
  } else {
    getPerActorTypeThreshold(cfg.thresholds)
  }

  private val exceedMaxNumberOfMessages: ArrayBuffer[ScheduledTrigger] = new ArrayBuffer[ScheduledTrigger]()

  private val class2Helper: Map[Class[_], StuckFinderHelper[ScheduledTrigger]] = if (!cfg.enabled) {
    Map.empty
  } else {
    cfg.thresholds.map { option =>
      val clazz = Class.forName(option.triggerType)
      (clazz, new StuckFinderHelper[ScheduledTrigger])
    }.toMap
  }

  private val class2Threshold: Map[Class[_], Long] = if (!cfg.enabled) {
    Map.empty
  } else {
    cfg.thresholds.map { option =>
      val clazz = Class.forName(option.triggerType)
      logger.info("{} => {} ms", clazz, option.markAsStuckAfterMs)
      (clazz, option.markAsStuckAfterMs)
    }.toMap
  }

  def updateTickIfNeeded(tick: Int): Unit = {
    if (tick != tickValue) {
      tickValue = tick
      lastUpdatedTime = System.currentTimeMillis()
    }
  }

  def add(time: Long, st: ScheduledTrigger, isNew: Boolean): Unit = {
    if (cfg.enabled) {
      updateTickIfNeeded(st.triggerWithId.trigger.tick)
      if (isNew && cfg.checkMaxNumberOfMessagesEnabled)
        checkIfExceedMaxNumOfMsgPerActorType(st)
      class2Helper
        .get(toKey(st))
        .foreach { helper =>
          helper.add(time, st)
        }
    }
  }

  def removeByKey(st: ScheduledTrigger): Option[ValueWithTime[ScheduledTrigger]] = {
    if (cfg.enabled) {
      class2Helper
        .get(toKey(st))
        .flatMap { helper =>
          helper.removeByKey(st)
        }
    } else
      None
  }

  def detectStuckAgents(
    time: Long = System.currentTimeMillis()
  ): Seq[ValueWithTime[ScheduledTrigger]] = {
    @tailrec
    def detectStuckAgents0(
      helper: StuckFinderHelper[ScheduledTrigger],
      stuckAgents: ArrayBuffer[ValueWithTime[ScheduledTrigger]]
    ): Seq[ValueWithTime[ScheduledTrigger]] = {
      helper.removeOldest() match {
        case Some(oldest) =>
          val isStuck: Boolean = isStuckAgent(oldest.value, oldest.time, time)
          if (!isStuck) {
            val stat = actorToTriggerMessages.get(oldest.value.agent)
            // We have to add it back
            add(oldest.time, oldest.value, false)
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
    if (cfg.enabled) {
      checkIfOverallSimulationIsStuck()
      class2Helper.values.foreach { helper =>
        detectStuckAgents0(helper, result)
      }
    }
    if (cfg.checkMaxNumberOfMessagesEnabled) {
      result.appendAll(exceedMaxNumberOfMessages.map { x =>
        ValueWithTime(x, -1)
      })
      exceedMaxNumberOfMessages.clear()
    }
    result
  }

  def isStuckAgent(st: ScheduledTrigger, startedAtMs: Long, currentTimeMs: Long): Boolean = {
    val diff = currentTimeMs - startedAtMs
    val threshold = class2Threshold.getOrElse(toKey(st), cfg.defaultTimeoutMs)
    val isStuck = diff > threshold
    if (isStuck) {
      logger.warn(s"$st is stuck. Diff: $diff ms, Threshold: $threshold ms")
    }
    isStuck
  }

  private def checkIfOverallSimulationIsStuck(): Unit = {
    if (tickValue != -1 && lastUpdatedTime != 0) {
      val diff = System.currentTimeMillis() - lastUpdatedTime
      val isStuck = diff > cfg.overallSimulationTimeoutMs
      if (isStuck) {
        numCriticalStuckMessages = numCriticalStuckMessages + 1
        if (MathUtils.isNumberPowerOfTwo(numCriticalStuckMessages)) {
          logger.error(s"Critical. No progress in overall simulation for last $diff ms")
        }
      }
    }
  }

  private def toKey(st: ScheduledTrigger): Class[_] = st.triggerWithId.trigger.getClass

  private def verifyTypesExist(): Unit = {
    // Make sure that those classes exist
    val definedTypes = cfg.thresholds.map { t =>
      // ClassNotFoundException  will be thrown if class does not exists or renamed or deleted
      Class.forName(t.triggerType)
    }

    val allSubClasses = new ReflectionUtils { val packageName = "beam.agentsim" }.classesOfType[Trigger]
    allSubClasses.diff(definedTypes).foreach { clazz =>
      logger.warn("There is no configuration for '{}'", clazz)
    }
  }

  private def checkIfExceedMaxNumOfMsgPerActorType(st: ScheduledTrigger): Unit = {
    val actor = st.agent
    val triggerClazz = st.triggerWithId.trigger.getClass
    val msgCount = updateAndGetNumOfTriggerMessagesPerActor(actor, triggerClazz)
    val maxMsgPerActorType = triggerTypeToActorThreshold
      .get(triggerClazz)
      .flatMap { m =>
        m.get(getActorType(actor))
      }
      .getOrElse(Int.MaxValue)
    val diff = maxMsgPerActorType - msgCount
    // Do we exceed the maxMsgPerActorType by 1?
    if (diff == -1) {
      exceedMaxNumberOfMessages.append(st)
      logger.warn(
        s"$st has exceeded max number of messages threshold. Trigger type: '$triggerClazz', current count: $msgCount, max: $maxMsgPerActorType"
      )
    }
  }

  private def updateAndGetNumOfTriggerMessagesPerActor(actor: ActorRef, triggerClazz: Class[_ <: Trigger]): Int = {
    val newCount = actorToTriggerMessages.get(actor) match {
      case Some(triggerTypeToOccur) =>
        triggerTypeToOccur.get(triggerClazz) match {
          case Some(current) =>
            triggerTypeToOccur.update(triggerClazz, current + 1)
            current + 1
          case None =>
            triggerTypeToOccur.put(triggerClazz, 1)
            1
        }
      case None =>
        actorToTriggerMessages.put(actor, mutable.Map[Class[_], Int](triggerClazz -> 1))
        1
    }
    newCount
  }

  private def getPerActorTypeThreshold(thresholds: Seq[Thresholds$Elm]): Map[Class[_], Map[String, Int]] = {
    thresholds.map { t =>
      val popilationOpt = t.actorTypeToMaxNumberOfMessages.population.map { n =>
        "Population" -> n
      }
      val rideHailManagerOpt = t.actorTypeToMaxNumberOfMessages.rideHailManager.map { n =>
        "RideHailManager" -> n
      }
      val rideHailAgentOpt = t.actorTypeToMaxNumberOfMessages.rideHailAgent.map { n =>
        "RideHailAgent" -> n
      }
      val transitDriverAgentOpt = t.actorTypeToMaxNumberOfMessages.transitDriverAgent.map { n =>
        "TransitDriverAgent" -> n
      }
      val actorTypeToMaxNumOfMessages =
        Seq(popilationOpt, rideHailManagerOpt, rideHailAgentOpt, transitDriverAgentOpt).flatten.toMap
      val clazz = Class.forName(t.triggerType)
      (clazz, actorTypeToMaxNumOfMessages)
    }.toMap
  }

  def getActorType(actorRef: ActorRef): String = {
    if (actorRef.path.parent.name == "router" && actorRef.path.name.indexOf("TransitDriverAgent-") != -1) {
      "TransitDriverAgent"
    } else if (actorRef.path.parent.name == "population" || actorRef.path.parent.parent.name == "population") {
      "Population"
    } else if (actorRef.path.name.contains("rideHailAgent-")) {
      "RideHailAgent"
    } else if (actorRef.path.name == "RideHailManager") {
      "RideHailManager"
    } else {
      actorRef.path.toString
    }
  }
}
