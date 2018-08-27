package beam.utils

import akka.actor.Actor
import beam.agentsim.agents.InitializeTrigger
import beam.agentsim.scheduler.BeamAgentScheduler.ScheduledTrigger
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.sim.config.BeamConfig.Beam.Debug.StuckAgentDetection
import org.scalatest.{Matchers, WordSpec}

class StuckFinderSpec extends WordSpec with Matchers {

  val stuckAgentDetectionCfg =
    StuckAgentDetection(enabled = true, checkIntervalMs = 100, markAsStuckAfterMs = 100)
  val st = ScheduledTrigger(TriggerWithId(InitializeTrigger(1.0), 1L), Actor.noSender, 1)

  "A StuckFinder" should {
    "return true" when {
      "it is stuck agent" in {
        val s = new StuckFinder(stuckAgentDetectionCfg)
        s.isStuckAgent(st, 0, stuckAgentDetectionCfg.markAsStuckAfterMs + 1)
      }
    }
    "return false" when {
      "it is not stuck agent" in {
        val s = new StuckFinder(stuckAgentDetectionCfg)
        s.isStuckAgent(st, 0, stuckAgentDetectionCfg.markAsStuckAfterMs - 1)
      }
    }
    "be able to detect stuck agents" in {
      val s = new StuckFinder(stuckAgentDetectionCfg)
      s.add(10, st.copy(priority = 10))
      s.add(5, st.copy(priority = 5))
      s.add(9, st.copy(priority = 9))
      s.add(2, st.copy(priority = 2))
      s.add(4, st.copy(priority = 4))
      s.add(7, st.copy(priority = 7))

      val seq = s.detectStuckAgents(stuckAgentDetectionCfg.markAsStuckAfterMs + 11)
      seq should be(
        Seq(
          ValueWithTime(st.copy(priority = 2), 2),
          ValueWithTime(st.copy(priority = 4), 4),
          ValueWithTime(st.copy(priority = 5), 5),
          ValueWithTime(st.copy(priority = 7), 7),
          ValueWithTime(st.copy(priority = 9), 9),
          ValueWithTime(st.copy(priority = 10), 10)
        )
      )
    }
  }
}
