package beam

import org.scalatest._
import Matchers._
import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestActorRef, TestFSMRef, TestKit}
import beam.metasim.agents.BeamAgent.{Uninitialized,Initialized}
import scala.concurrent.duration._
import org.scalatest.{FunSpecLike, MustMatchers}
import beam.metasim.agents._

class BeamAgentSchedulerSpec extends TestKit(ActorSystem("beam-actor-system")) with MustMatchers with FunSpecLike with ImplicitSender  {

  case class TestTrigger(override val data: TriggerData) extends Trigger

  describe("BEAM Scheduler"){
      it("should send trigger to a BeamAgent"){
        val beamAgentSchedulerRef = TestActorRef[BeamAgentScheduler]
        val beamAgentRef = TestFSMRef(new BeamAgent)
        beamAgentRef.stateName should be(Uninitialized)
        beamAgentSchedulerRef ! Initialize(new TriggerData(beamAgentRef, 0.0))
        beamAgentRef.stateName should be(Uninitialized)
        beamAgentSchedulerRef ! StartSchedule(stopTick = 10.0)
        beamAgentRef.stateName should be(Initialized)
      }
  }

}
