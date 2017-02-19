package beam.metasim.agents


import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestActorRef, TestFSMRef, TestKit}
import beam.metasim.agents.PersonAgent._
import org.matsim.api.core.v01.Id
import org.matsim.core.population.PopulationUtils
import org.scalatest.Matchers._
import org.scalatest.{FunSpecLike, MustMatchers}

/**
  * Created by sfeygin on 2/7/17.
  */
class PersonAgentSpec extends TestKit(ActorSystem("beam-actor-system"))
  with MustMatchers with FunSpecLike with ImplicitSender  {

  describe("PersonAgent FSM"){

      it("should allow scheduler to set the first activity"){
        val homeActivity = PopulationUtils.createActivityFromLinkId("home", Id.createLinkId(1))
        val personAgentRef = TestFSMRef(new PersonAgent(Id.create("dummyAgent",classOf[PersonAgent]), PersonAgentData(homeActivity)))
        val beamAgentSchedulerRef = TestActorRef[BeamAgentScheduler]

        val workActivity = PopulationUtils.createActivityFromLinkId("work", Id.createLinkId(2))
        beamAgentSchedulerRef ! Initialize(new TriggerData(personAgentRef, 0.0))

        beamAgentSchedulerRef ! DepartActivity(new TriggerData(personAgentRef, 1.0), homeActivity)
        beamAgentSchedulerRef ! DepartActivity(new TriggerData(personAgentRef, 10.0), workActivity)
        beamAgentSchedulerRef ! StartSchedule(stopTick = 10.0, maxWindow = 10.0)

        personAgentRef.stateName should be(ChoosingMode)
        personAgentRef.stateData.data.currentPlanElement should be(workActivity)
      }
  }

}
