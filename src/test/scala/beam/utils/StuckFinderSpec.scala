package beam.utils

import akka.actor.ActorSystem
import akka.testkit.{TestActors, TestKit}
import beam.agentsim.agents.InitializeTrigger
import beam.agentsim.scheduler.BeamAgentScheduler.ScheduledTrigger
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.sim.config.BeamConfig.Beam.Debug.StuckAgentDetection
import beam.sim.config.BeamConfig.Beam.Debug.StuckAgentDetection.Thresholds$Elm
import beam.sim.config.BeamConfig.Beam.Debug.StuckAgentDetection.Thresholds$Elm.ActorTypeToMaxNumberOfMessages
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

class StuckFinderSpec
    extends TestKit(ActorSystem("StuckFinderSpec"))
    with WordSpecLike
    with Matchers
    with BeforeAndAfterAll {

  override def afterAll: Unit = {
    TestKit.shutdownActorSystem(system)
  }

  val threshold = Thresholds$Elm(
    ActorTypeToMaxNumberOfMessages(Some(100), Some(100), Some(100), Some(100)),
    100,
    classOf[InitializeTrigger].getCanonicalName
  )

  val stuckAgentDetectionCfg = StuckAgentDetection(
    enabled = true,
    checkMaxNumberOfMessagesEnabled = true,
    defaultTimeoutMs = 100,
    checkIntervalMs = 100,
    overallSimulationTimeoutMs = 60000,
    thresholds = List(threshold)
  )

  val devNull = system.actorOf(TestActors.blackholeProps)
  val st = ScheduledTrigger(TriggerWithId(InitializeTrigger(1), 1L), devNull, 1)

  "A StuckFinder" should {
    "return true" when {
      "it is stuck agent" in {
        val s = new StuckFinder(stuckAgentDetectionCfg)
        s.isStuckAgent(st, 0, threshold.markAsStuckAfterMs + 1)
      }
    }
    "return false" when {
      "it is not stuck agent" in {
        val s = new StuckFinder(stuckAgentDetectionCfg)
        s.isStuckAgent(st, 0, threshold.markAsStuckAfterMs - 1)
      }
    }
    "be able to detect stuck agents" in {
      val s = new StuckFinder(stuckAgentDetectionCfg)
      s.add(10, st.copy(priority = 10), isNew = true)
      s.add(5, st.copy(priority = 5), isNew = true)
      s.add(9, st.copy(priority = 9), isNew = true)
      s.add(2, st.copy(priority = 2), isNew = true)
      s.add(4, st.copy(priority = 4), isNew = true)
      s.add(7, st.copy(priority = 7), isNew = true)

      val seq = s.detectStuckAgents(threshold.markAsStuckAfterMs + 11)
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
