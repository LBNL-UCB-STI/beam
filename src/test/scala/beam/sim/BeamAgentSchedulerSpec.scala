package beam.sim

import akka.actor.{Actor, ActorRef, ActorSystem}
import akka.event.Logging
import akka.pattern.ask
import akka.testkit.{ImplicitSender, TestActorRef, TestFSMRef, TestKit}
import beam.agentsim.agents.BeamAgent._
import beam.agentsim.agents.BeamAgent.NoData
import beam.agentsim.agents._
import beam.agentsim.scheduler.{BeamAgentScheduler, Trigger, TriggerWithId}
import beam.agentsim.scheduler.BeamAgentScheduler._
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person
import org.scalatest.Matchers._
import org.scalatest.exceptions.TestFailedException
import org.scalatest.{FunSpecLike, MustMatchers}

import scala.concurrent.Await
import scala.concurrent.duration._

class BeamAgentSchedulerSpec extends TestKit(ActorSystem("beam-actor-system")) with MustMatchers with FunSpecLike with ImplicitSender {

  describe("A BEAM Agent Scheduler") {
    it("should send trigger to a BeamAgent") {
      val beamAgentSchedulerRef = TestActorRef[BeamAgentScheduler](SchedulerProps(stopTick = 10.0, maxWindow = 10.0))
      val beamAgentRef = TestFSMRef(new TestBeamAgent(Id.createPersonId(0)))
      beamAgentRef.stateName should be(Uninitialized)
      beamAgentSchedulerRef ! ScheduleTrigger(InitializeTrigger(0.0),beamAgentRef)
      beamAgentRef.stateName should be(Uninitialized)
      beamAgentSchedulerRef ! StartSchedule
      beamAgentRef.stateName should be(Initialized)
    }
    it("should fail to schedule events with negative tick value") {
      val beamAgentSchedulerRef = TestActorRef[BeamAgentScheduler]
      val beamAgentRef = TestFSMRef(new TestBeamAgent(Id.createPersonId(0)))
      val thrown = intercept[Exception] {
        beamAgentSchedulerRef ! ScheduleTrigger(InitializeTrigger(-1),beamAgentRef)
      }
      thrown.getClass should be(classOf[IllegalArgumentException])
    }
    it("should allow for addition of non-chronological triggers") {
      val beamAgentSchedulerRef = TestActorRef[BeamAgentScheduler]
      val beamAgentRef = TestFSMRef(new TestBeamAgent(Id.createPersonId(0)))
      val thrownTest = intercept[Exception] {
        val thrown = intercept[Exception] {
          beamAgentSchedulerRef ! ScheduleTrigger(InitializeTrigger(0.0), beamAgentRef)
          beamAgentSchedulerRef ! ScheduleTrigger(InitializeTrigger(10.0), beamAgentRef)
          beamAgentSchedulerRef ! ScheduleTrigger(InitializeTrigger(5.0), beamAgentRef)
          beamAgentSchedulerRef ! ScheduleTrigger(InitializeTrigger(15.0), beamAgentRef)
          beamAgentSchedulerRef ! ScheduleTrigger(InitializeTrigger(9.0), beamAgentRef)
        }
      }
      thrownTest.getClass should be(classOf[TestFailedException])
    }
    it("should dispatch triggers in chronological order") {
      val beamAgentSchedulerRef = TestActorRef[BeamAgentScheduler](SchedulerProps(stopTick = 100.0, maxWindow = 100.0))
      val testReporter = TestActorRef[TestReporter]
      val beamAgentRef = TestFSMRef(new TestBeamAgent(Id.createPersonId(0)) {
        override val reporterActor: ActorRef = testReporter.actorRef
      })
      beamAgentSchedulerRef ! ScheduleTrigger(InitializeTrigger(0.0),beamAgentRef)
      beamAgentSchedulerRef ! ScheduleTrigger(InitializeTrigger(0.0),beamAgentRef)
      beamAgentSchedulerRef ! ScheduleTrigger(ReportState(1.0),beamAgentRef)
      beamAgentSchedulerRef ! ScheduleTrigger(ReportState(10.0),beamAgentRef)
      beamAgentSchedulerRef ! ScheduleTrigger(ReportState(5.0),beamAgentRef)
      beamAgentSchedulerRef ! ScheduleTrigger(ReportState(15.0),beamAgentRef)
      beamAgentSchedulerRef ! ScheduleTrigger(ReportState(9.0),beamAgentRef)
      beamAgentSchedulerRef ! StartSchedule
      Thread.sleep(100)
      beamAgentRef.stateName should be(Reporting)
      val future = testReporter.ask(ReportBack)(1 second)
      val result = Await.result(future, 1 second).asInstanceOf[List[String]]
      result should be(Seq("15.0", "10.0", "9.0", "5.0", "1.0"))
    }
    it("should not dispatch triggers beyond a window when old triggers have not completed") {}
    //    it(""){}
  }
}

case class ReportState(val tick: Double) extends Trigger

case object Reporting extends BeamAgentState {
  override def identifier = "Reporting"
}

class TestBeamAgent(override val id: Id[Person]) extends BeamAgent[NoData] {
  override def data = NoData()
  override def logPrefix(): String = "TestBeamAgent"

  val reporterActor: ActorRef = null

  when(Initialized) {
    case Event(TriggerWithId(_,triggerId),_) =>
      goto(Reporting) replying CompletionNotice(triggerId)
  }
  when(Reporting) {
    case Event(TriggerWithId(ReportState(tick),triggerId),_) =>
      reporterActor ! tick.toString
      stay()
    case msg =>
      log.warning("unhandled " + msg + " from state Reporting")
      stay()
  }

}

case object ReportBack

case class SendReporter(reporter: TestActorRef[TestReporter])

object TestReporter

class TestReporter extends Actor {
  val log = Logging(context.system, this)
  var messages: List[String] = List[String]()

  def receive: Receive = {
    case newMsg: String =>
      messages = newMsg :: messages
      log.info("Msg now: " + messages.toString())
    case ReportBack =>
      sender() ! messages
  }
}
