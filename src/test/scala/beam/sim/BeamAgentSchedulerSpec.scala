package beam.sim

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{ImplicitSender, TestActorRef, TestFSMRef, TestKit}
import beam.agentsim.agents.BeamAgent._
import beam.agentsim.agents._
import beam.agentsim.scheduler.BeamAgentScheduler._
import beam.agentsim.scheduler.{BeamAgentScheduler, Trigger, TriggerWithId}
import beam.sim.BeamAgentSchedulerSpec._
import beam.sim.config.BeamConfig
import beam.utils.TestConfigUtils.testConfig
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person
import org.matsim.core.events.EventsManagerImpl
import org.scalatest.Matchers._
import org.scalatest.{BeforeAndAfterAll, FunSpecLike, MustMatchers}

class BeamAgentSchedulerSpec extends TestKit(ActorSystem("beam-actor-system", testConfig("test/input/beamville/beam.conf"))) with FunSpecLike with BeforeAndAfterAll with MustMatchers with ImplicitSender {

  val config = BeamConfig(system.settings.config)

  describe("A BEAM Agent Scheduler") {

    it("should send trigger to a BeamAgent") {
      val scheduler = TestActorRef[BeamAgentScheduler](SchedulerProps(config, stopTick = 10.0, maxWindow = 10.0))
      val agent = TestFSMRef(new TestBeamAgent(Id.createPersonId(0), scheduler))
      agent.stateName should be(Uninitialized)
      scheduler ! ScheduleTrigger(InitializeTrigger(0.0), agent)
      agent.stateName should be(Uninitialized)
      scheduler ! StartSchedule(0)
      agent.stateName should be(Initialized)
      agent ! Finish
      expectMsg(CompletionNotice(0L))
    }

    it("should fail to schedule events with negative tick value") {
      val scheduler = TestActorRef[BeamAgentScheduler](SchedulerProps(config, stopTick = 10.0, maxWindow = 0.0))
      val agent = TestFSMRef(new TestBeamAgent(Id.createPersonId(0), scheduler))
      watch(agent)
      scheduler ! ScheduleTrigger(InitializeTrigger(-1), agent)
      expectTerminated(agent)
    }

    it("should dispatch triggers in chronological order") {
      val scheduler = TestActorRef[BeamAgentScheduler](SchedulerProps(config, stopTick = 100.0, maxWindow = 100.0))
      scheduler ! ScheduleTrigger(InitializeTrigger(0.0), self)
      scheduler ! ScheduleTrigger(ReportState(1.0), self)
      scheduler ! ScheduleTrigger(ReportState(10.0), self)
      scheduler ! ScheduleTrigger(ReportState(5.0), self)
      scheduler ! ScheduleTrigger(ReportState(15.0), self)
      scheduler ! ScheduleTrigger(ReportState(9.0), self)
      scheduler ! StartSchedule(0)
      expectMsg(TriggerWithId(InitializeTrigger(0.0), 1))
      scheduler ! CompletionNotice(1)
      expectMsg(TriggerWithId(ReportState(1.0), 2))
      scheduler ! CompletionNotice(2)
      expectMsg(TriggerWithId(ReportState(5.0), 4))
      scheduler ! CompletionNotice(4)
      expectMsg(TriggerWithId(ReportState(9.0), 6))
      scheduler ! CompletionNotice(6)
      expectMsg(TriggerWithId(ReportState(10.0), 3))
      scheduler ! CompletionNotice(3)
      expectMsg(TriggerWithId(ReportState(15.0), 5))
      scheduler ! CompletionNotice(5)
      expectMsg(CompletionNotice(0L))
    }
  }

  override def afterAll: Unit = {
    shutdown()
  }

}

object BeamAgentSchedulerSpec {

  case class MyData()

  case class ReportState(tick: Double) extends Trigger

  class TestBeamAgent(override val id: Id[Person], override val scheduler: ActorRef) extends BeamAgent[MyData] {
    val eventsManager = new EventsManagerImpl

    override def logPrefix(): String = "TestBeamAgent"

    startWith(Uninitialized, MyData())

    when(Uninitialized) {
      case Event(TriggerWithId(InitializeTrigger(_), triggerId), _) =>
        goto(Initialized) replying CompletionNotice(triggerId, Vector())
    }
    when(Initialized) {
      case Event(TriggerWithId(_, triggerId), _) =>
        stay() replying CompletionNotice(triggerId, Vector())
    }
    whenUnhandled {
      case Event(IllegalTriggerGoToError(_), _) =>
        stop
      case Event(Finish, _) =>
        stop
    }
  }

  case object Reporting extends BeamAgentState

}
