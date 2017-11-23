package beam.sim

import java.io.File

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestActorRef, TestFSMRef, TestKit}
import beam.agentsim.agents.BeamAgent.{NoData, _}
import beam.agentsim.agents.TriggerUtils.completed
import beam.agentsim.agents._
import beam.agentsim.scheduler.BeamAgentScheduler._
import beam.agentsim.scheduler.{BeamAgentScheduler, Trigger, TriggerWithId}
import beam.sim.BeamAgentSchedulerSpec._
import beam.sim.config.BeamConfig
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person
import org.scalatest.Matchers._
import org.scalatest.{FunSpecLike, MustMatchers}

class BeamAgentSchedulerSpec extends TestKit(ActorSystem("beam-actor-system")) with MustMatchers with FunSpecLike with ImplicitSender {

  val config = BeamConfig(ConfigFactory.parseFile(new File("test/input/beamville/beam.conf")).resolve())

  describe("A BEAM Agent Scheduler") {

    it("should send trigger to a BeamAgent") {
      val beamAgentSchedulerRef = TestActorRef[BeamAgentScheduler](SchedulerProps(config, stopTick = 10.0, maxWindow = 10.0))
      val beamAgentRef = TestFSMRef(new TestBeamAgent(Id.createPersonId(0)))
      beamAgentRef.stateName should be(Uninitialized)
      beamAgentSchedulerRef ! ScheduleTrigger(InitializeTrigger(0.0),beamAgentRef)
      beamAgentRef.stateName should be(Uninitialized)
      beamAgentSchedulerRef ! StartSchedule(0)
      beamAgentRef.stateName should be(Initialized)
    }

    it("should fail to schedule events with negative tick value") {
      val beamAgentSchedulerRef = TestActorRef[BeamAgentScheduler](SchedulerProps(config, stopTick = 10.0, maxWindow = 0.0))
      val beamAgentRef = TestFSMRef(new TestBeamAgent(Id.createPersonId(0)))
      watch(beamAgentRef)
      beamAgentSchedulerRef ! ScheduleTrigger(InitializeTrigger(-1),beamAgentRef)
      expectTerminated(beamAgentRef)
    }

    it("should dispatch triggers in chronological order") {
      val beamAgentSchedulerRef = TestActorRef[BeamAgentScheduler](SchedulerProps(config, stopTick = 100.0, maxWindow = 100.0))
      beamAgentSchedulerRef ! ScheduleTrigger(InitializeTrigger(0.0), self)
      beamAgentSchedulerRef ! ScheduleTrigger(ReportState(1.0), self)
      beamAgentSchedulerRef ! ScheduleTrigger(ReportState(10.0), self)
      beamAgentSchedulerRef ! ScheduleTrigger(ReportState(5.0), self)
      beamAgentSchedulerRef ! ScheduleTrigger(ReportState(15.0), self)
      beamAgentSchedulerRef ! ScheduleTrigger(ReportState(9.0), self)
      beamAgentSchedulerRef ! StartSchedule(0)
      expectMsg(TriggerWithId(InitializeTrigger(0.0), 1))
      beamAgentSchedulerRef ! completed(1)
      expectMsg(TriggerWithId(ReportState(1.0), 2))
      beamAgentSchedulerRef ! completed(2)
      expectMsg(TriggerWithId(ReportState(5.0), 4))
      beamAgentSchedulerRef ! completed(4)
      expectMsg(TriggerWithId(ReportState(9.0), 6))
      beamAgentSchedulerRef ! completed(6)
      expectMsg(TriggerWithId(ReportState(10.0), 3))
      beamAgentSchedulerRef ! completed(3)
      expectMsg(TriggerWithId(ReportState(15.0), 5))
      beamAgentSchedulerRef ! completed(5)
    }
  }
}

object BeamAgentSchedulerSpec {

  case class ReportState(val tick: Double) extends Trigger

  case object Reporting extends BeamAgentState {
    override def identifier = "Reporting"
  }

  class TestBeamAgent(override val id: Id[Person]) extends BeamAgent[NoData] {
    override def data = NoData()

    override def logPrefix(): String = "TestBeamAgent"

    chainedWhen(Uninitialized){
      case Event(TriggerWithId(InitializeTrigger(tick), triggerId), _) =>
        goto(Initialized) replying completed(triggerId, Vector())
    }
    chainedWhen(Initialized) {
      case msg@Event(TriggerWithId(_, triggerId), _) =>
        stay() replying completed(triggerId, Vector())
    }
    chainedWhen(AnyState) {
      case Event(IllegalTriggerGoToError, _) =>
        stop
    }
  }

}
