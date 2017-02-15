package beam

import org.scalatest._
import Matchers._

import scala.concurrent.duration._
import akka.actor.{Actor, ActorSystem, ActorRef}
import akka.event.Logging
import akka.testkit.{ImplicitSender, TestActorRef, TestFSMRef, TestKit}
import beam.metasim.agents.BeamAgent.{BeamState, Initialized, Uninitialized}
import beam.metasim.agents.PersonAgent.PersonAgentInfo
import akka.pattern.ask
import org.scalatest.{FunSpecLike, MustMatchers}
import beam.metasim.agents._
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person
import org.scalatest.concurrent.PatienceConfiguration.Timeout

import scala.concurrent.Await

class BeamAgentSchedulerSpec extends TestKit(ActorSystem("beam-actor-system")) with MustMatchers with FunSpecLike with ImplicitSender  {

  describe("BEAM Agent Scheduler") {
    it("should send trigger to a BeamAgent") {
      val beamAgentSchedulerRef = TestActorRef[BeamAgentScheduler]
      val beamAgentRef = TestFSMRef(new TestBeamAgent(Id.createPersonId(0)))
      beamAgentRef.stateName should be(Uninitialized)
      beamAgentSchedulerRef ! Initialize(new TriggerData(beamAgentRef, 0.0))
      beamAgentRef.stateName should be(Uninitialized)
      beamAgentSchedulerRef ! StartSchedule(stopTick = 10.0, maxWindow = 10.0)
      beamAgentRef.stateName should be(Initialized)
    }
    it("should fail to schedule events with negative tick value") {
      val beamAgentSchedulerRef = TestActorRef[BeamAgentScheduler]
      val beamAgentRef = TestFSMRef(new TestBeamAgent(Id.createPersonId(0)))
      val thrown = intercept[Exception] {
        beamAgentSchedulerRef ! Initialize(new TriggerData(beamAgentRef, -1.0))
      }
      thrown.getClass should be(classOf[IllegalArgumentException])
    }
    it("should allow for addition of non-chronological triggers") {
      val beamAgentSchedulerRef = TestActorRef[BeamAgentScheduler]
      val beamAgentRef = TestFSMRef(new TestBeamAgent(Id.createPersonId(0)))
      beamAgentSchedulerRef ! Initialize(new TriggerData(beamAgentRef, 0.0))
      beamAgentSchedulerRef ! Initialize(new TriggerData(beamAgentRef, 10.0))
      beamAgentSchedulerRef ! Initialize(new TriggerData(beamAgentRef, 5.0))
      beamAgentSchedulerRef ! Initialize(new TriggerData(beamAgentRef, 15.0))
      beamAgentSchedulerRef ! Initialize(new TriggerData(beamAgentRef, 9.0))
    }
    it("should dispatch triggers in chronological order") {
      val beamAgentSchedulerRef = TestActorRef[BeamAgentScheduler]
      val testReporter = TestActorRef[TestReporter]
      val beamAgentRef = TestFSMRef(new TestBeamAgent(Id.createPersonId(0)){
        override val reporterActor = testReporter.actorRef
      })
      beamAgentRef ! Initialize(new TriggerData(beamAgentRef, 0.0))
      beamAgentRef.stateName should be(Initialized)
      beamAgentRef ! testReporter
      beamAgentRef.stateName should be(Reporting)
      beamAgentSchedulerRef ! ReportState(new TriggerData(beamAgentRef, 0.0))
      beamAgentSchedulerRef ! ReportState(new TriggerData(beamAgentRef, 10.0))
      beamAgentSchedulerRef ! ReportState(new TriggerData(beamAgentRef, 5.0))
      beamAgentSchedulerRef ! ReportState(new TriggerData(beamAgentRef, 15.0))
      beamAgentSchedulerRef ! ReportState(new TriggerData(beamAgentRef, 9.0))
      beamAgentSchedulerRef ! StartSchedule(stopTick = 100.0, maxWindow = 100.0)
      Thread.sleep(100)
      val future = testReporter.ask(ReportBack)(1 second)
      val result = Await.result(future, 1 second).asInstanceOf[List[String]]
      result should be(Seq("15.0","10.0","9.0","5.0","0.0"))
    }
    it("should not dispatch triggers beyond a window when old triggers have not completed") {}
    //    it(""){}
  }
}
case class ReportState(override val triggerData: TriggerData) extends Trigger
case object Reporting extends BeamState{
  override def identifier = "Reporting"
}
class TestBeamAgent(override val id: Id[Person]) extends BeamAgent(id){
  val reporterActor: ActorRef = null

  when(Initialized) {
    case _ => {
      goto(Reporting)
    }
  }
  when(Reporting) {
    case Event(ReportState(triggerData),_) =>{
      reporterActor ! triggerData.tick.toString
      stay()
    }
    case Event(msg,_) => {
      log.warning("unhandled " + msg + " from state Reporting")
      stay()
    }
  }
}
case object ReportBack
case class SendReporter(reporter: TestActorRef[TestReporter])
object TestReporter
class TestReporter extends Actor{
  val log = Logging(context.system, this)
  var messages: List[String] = List[String]()

  def receive = {
    case newMsg: String => {
      messages = newMsg :: messages
      log.info("Msg now: "+messages.toString())
    }
    case ReportBack => {
      sender() ! messages
    }
  }
}
