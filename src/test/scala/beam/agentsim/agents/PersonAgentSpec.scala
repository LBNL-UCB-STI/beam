package beam.agentsim.agents


import java.util.concurrent.TimeUnit

import akka.actor.{ActorSystem, Props}
import akka.pattern.ask
import akka.testkit.{ImplicitSender, TestActorRef, TestFSMRef, TestKit}
import akka.util.Timeout
import beam.agentsim.agents.PersonAgent._
import glokka.Registry
import glokka.Registry.Created
import org.matsim.api.core.v01.Id
import org.matsim.core.population.PopulationUtils
import org.scalatest.Matchers._
import org.scalatest.{FunSpecLike, MustMatchers}

import scala.concurrent.Await

/**
  * Created by sfeygin on 2/7/17.
  */
class PersonAgentSpec extends TestKit(ActorSystem("beam-actor-system"))
  with MustMatchers with FunSpecLike with ImplicitSender {

  private implicit val timeout = Timeout(60, TimeUnit.SECONDS)

  describe("PersonAgent FSM") {

    it("should allow scheduler to set the first activity") {
      val homeActivity = PopulationUtils.createActivityFromLinkId("home", Id.createLinkId(1))
      val workActivity = PopulationUtils.createActivityFromLinkId("work", Id.createLinkId(2))
      val data = PersonData(Vector(homeActivity, workActivity), 0)

      val personAgentRef = TestFSMRef(new PersonAgent(Id.create("dummyAgent", classOf[PersonAgent]), data))
      val beamAgentSchedulerRef = TestActorRef[BeamAgentScheduler]

      beamAgentSchedulerRef ! Initialize(new TriggerData(personAgentRef, 0.0))
      beamAgentSchedulerRef ! ActivityStartTrigger(new TriggerData(personAgentRef, 1.0))
      beamAgentSchedulerRef ! ActivityEndTrigger(new TriggerData(personAgentRef, 10.0))
      beamAgentSchedulerRef ! StartSchedule(stopTick = 11.0, maxWindow = 10.0)

      personAgentRef.stateName should be(ChoosingMode)
      personAgentRef.stateData.data.getCurrentActivity should be(workActivity)
    }

    it("should be able to be registered in registry") {
      val registry = Registry.start(this.system, "actor-registry")
      val homeActivity = PopulationUtils.createActivityFromLinkId("home", Id.createLinkId(1))
      val name = "0"
      val data = PersonData(Vector(homeActivity), 0)
      val props = Props(classOf[PersonAgent], Id.createPersonId(name), data)
      val future = registry ? Registry.Register(name, props)
      val result = Await.result(future, timeout.duration).asInstanceOf[AnyRef]
      val ok = result.asInstanceOf[Created]
      ok.name mustEqual name
    }

    it("should demonstrate a simple complete daily activity pattern"){

    }
  }

}
