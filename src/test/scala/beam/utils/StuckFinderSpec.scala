package beam.utils

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{TestActors, TestKit}
import beam.agentsim.agents.InitializeTrigger
import beam.agentsim.agents.modalbehaviors.DrivesVehicle.BoardVehicleTrigger
import beam.agentsim.scheduler.BeamAgentScheduler.ScheduledTrigger
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.sim.config.BeamConfig.Beam.Debug.StuckAgentDetection
import beam.sim.config.BeamConfig.Beam.Debug.StuckAgentDetection.Thresholds$Elm
import beam.sim.config.BeamConfig.Beam.Debug.StuckAgentDetection.Thresholds$Elm.ActorTypeToMaxNumberOfMessages
import org.matsim.api.core.v01.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.BeforeAndAfterAll

class StuckFinderSpec
    extends TestKit(ActorSystem("StuckFinderSpec"))
    with AnyWordSpecLike
    with Matchers
    with BeforeAndAfterAll {

  override def afterAll: Unit = {
    TestKit.shutdownActorSystem(system)
  }

  val threshold: Thresholds$Elm = Thresholds$Elm(
    ActorTypeToMaxNumberOfMessages(Some(100), Some(100), Some(100), Some(100)),
    100,
    classOf[InitializeTrigger].getName
  )

  val threshold2: Thresholds$Elm = Thresholds$Elm(
    ActorTypeToMaxNumberOfMessages(Some(3), Some(3), Some(3), Some(3)),
    100,
    classOf[BoardVehicleTrigger].getName
  )

  val stuckAgentDetectionCfg: StuckAgentDetection =
    StuckAgentDetection(
      enabled = true,
      checkMaxNumberOfMessagesEnabled = true,
      defaultTimeoutMs = 100,
      checkIntervalMs = 100,
      overallSimulationTimeoutMs = 60000,
      thresholds = List(threshold, threshold2)
    )

  val devNull: ActorRef = system.actorOf(TestActors.blackholeProps)
  val st: ScheduledTrigger = ScheduledTrigger(TriggerWithId(InitializeTrigger(1), 1L), devNull, 1)

  val boardVehicleTrigger: ScheduledTrigger =
    ScheduledTrigger(TriggerWithId(BoardVehicleTrigger(1, Id.createVehicleId(1)), 1L), devNull, 1)

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

    "should return stuck agent due to exceed number of messages only once" in {
      // Need to override the behaviour of `getActorType`
      val s = new StuckFinder(stuckAgentDetectionCfg) {
        override def getActorType(actorRef: ActorRef): String = "Population"
      }
      // Allowed max number of messages is 3
      (1 to 3).foreach { time =>
        println(time)
        s.add(time, boardVehicleTrigger, isNew = true)
      }
      s.detectStuckAgents(0) should be(Seq.empty)

      // It reaches max number of messages
      s.add(1, boardVehicleTrigger, isNew = true)

      // We have to find that agent it the stuck list
      val stuck = s.detectStuckAgents(0)
      stuck.head should be(ValueWithTime(boardVehicleTrigger, -1))

      // Next call to `detectStuckAgents(0)` should not return it anymore
      s.detectStuckAgents(0) should be(Seq.empty)
      s.detectStuckAgents(0) should be(Seq.empty)
      s.detectStuckAgents(0) should be(Seq.empty)

      // Even when we add it again
      s.add(1, boardVehicleTrigger, isNew = true)
      s.detectStuckAgents(0) should be(Seq.empty)
      s.detectStuckAgents(0) should be(Seq.empty)
    }
  }
}
