package beam

import org.scalatest._
import Matchers._
import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestActorRef, TestFSMRef, TestKit}
import beam.metasim.agents.BeamAgent.{Initialized, Uninitialized}

import scala.concurrent.duration._
import org.scalatest.{FunSpecLike, MustMatchers}
import beam.metasim.agents._
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person

class BeamAgentSchedulerSpec extends TestKit(ActorSystem("beam-actor-system")) with MustMatchers with FunSpecLike with ImplicitSender  {

  class TestFSMAgent(override val id: Id[Person]) extends BeamAgent(id)

  describe("BEAM Scheduler"){
      it("should send trigger to a BeamAgent"){
        val beamAgentSchedulerRef = TestActorRef[BeamAgentScheduler]
        val beamAgentRef = TestFSMRef(new TestFSMAgent(Id.createPersonId(0)))
        beamAgentRef.stateName should be(Uninitialized)
        beamAgentSchedulerRef ! Initialize(new TriggerData(beamAgentRef, 0.0))
        beamAgentRef.stateName should be(Uninitialized)
        beamAgentSchedulerRef ! StartSchedule(stopTick = 10.0)
        beamAgentRef.stateName should be(Initialized)
      }
  }

}
