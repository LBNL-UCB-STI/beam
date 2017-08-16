package beam.agentsim.agents

import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.testkit.{EventFilter, ImplicitSender, TestActorRef, TestFSMRef, TestKit}
import akka.util.Timeout
import beam.agentsim.agents.BeamAgent.Initialized
import beam.agentsim.scheduler.BeamAgentScheduler.{ScheduleTrigger, StartSchedule}
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.events.{AgentsimEventsBus, EventsSubscriber}
import beam.agentsim.scheduler.BeamAgentScheduler
import beam.agentsim.scheduler.BeamAgentScheduler.SchedulerProps
import beam.sim.BeamServices
import glokka.Registry
import glokka.Registry.Created
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.ActivityEndEvent
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.events.EventsUtils
import org.matsim.core.population.PopulationUtils
import org.matsim.facilities.ActivityFacility
import org.matsim.households.Household
import org.matsim.vehicles.Vehicle
import org.scalatest.Matchers._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{FunSpecLike, MustMatchers}

import scala.concurrent.Await

/**
  * Created by sfeygin on 2/7/17.
  */
class PersonAgentSpec extends TestKit(ActorSystem("testsystem"))
  with MustMatchers with FunSpecLike with ImplicitSender with MockitoSugar{

  private implicit val timeout = Timeout(60, TimeUnit.SECONDS)
  private val agentSimEventsBus = new AgentsimEventsBus

  val services: BeamServices = mock[BeamServices]

  describe("A PersonAgent FSM") {

    it("should allow scheduler to set the first activity") {
      val houseIdDummy = Id.create("dummy",classOf[Household])
      val homeActivity = PopulationUtils.createActivityFromLinkId("home", Id.createLinkId(1))
      homeActivity.setStartTime(1.0)
      homeActivity.setEndTime(10.0)
      val plan = PopulationUtils.getFactory.createPlan()
      plan.addActivity(homeActivity)

      val personAgentRef = TestFSMRef(new PersonAgent(services, Id.create("dummyAgent", classOf[PersonAgent]), houseIdDummy, plan, Id.create("dummyBody", classOf[Vehicle]),PersonData()))
      val beamAgentSchedulerRef = TestActorRef[BeamAgentScheduler](SchedulerProps(stopTick = 11.0, maxWindow = 10.0))

      beamAgentSchedulerRef ! ScheduleTrigger(InitializeTrigger(0.0),personAgentRef)
      beamAgentSchedulerRef ! StartSchedule

      personAgentRef.stateName should be(Initialized)
    }

    it("should be able to be registered in registry") {
      val registry = Registry.start(this.system, "actor-registry")
      val homeActivity = PopulationUtils.createActivityFromLinkId("home", Id.createLinkId(1))
      val name = "0"
      val plan = PopulationUtils.getFactory.createPlan()
      plan.addActivity(homeActivity)
      val props = Props(classOf[PersonAgent], Id.createPersonId(name),plan, Id.create("dummyBody", classOf[Vehicle]), PersonData())
      val future = registry ? Registry.Register(name, props)
      val result = Await.result(future, timeout.duration).asInstanceOf[AnyRef]
      val ok = result.asInstanceOf[Created]
      ok.name mustEqual name
    }


    it("should publish events that can be received by a MATSim EventsManager") {

      val houseIdDummy = Id.create("dummy",classOf[Household])
      val events: EventsManager = EventsUtils.createEventsManager()
      val eventSubscriber: ActorRef = TestActorRef(new EventsSubscriber(events), "events-subscriber1")
      val actEndDummy = new ActivityEndEvent(0, Id.createPersonId(0), Id.createLinkId(0), Id.create(0, classOf[ActivityFacility]), "dummy")
      agentSimEventsBus.subscribe(eventSubscriber, ActivityEndEvent.EVENT_TYPE)

      val plan = PopulationUtils.getFactory.createPlan()
      val homeActivity = PopulationUtils.createActivityFromLinkId("home", Id.createLinkId(1))
      homeActivity.setEndTime(28800)  // 8:00:00 AM
      plan.addActivity(homeActivity)
      val workActivity = PopulationUtils.createActivityFromLinkId("work", Id.createLinkId(2))
      workActivity.setEndTime(61200) //5:00:00 PM
      plan.addActivity(workActivity)
//        choiceCalculator = { (trips: Vector[BeamTrip], weights: Vector[Double] ) => trips.head }, currentVehicle = None)

      val personAgentRef = TestFSMRef(new PersonAgent(services, Id.create("dummyAgent", classOf[PersonAgent]), houseIdDummy, plan, Id.create("dummyBody", classOf[Vehicle]), PersonData()))
      val beamAgentSchedulerRef = TestActorRef[BeamAgentScheduler](SchedulerProps(stopTick = 11.0, maxWindow = 10.0))

      beamAgentSchedulerRef ! ScheduleTrigger(InitializeTrigger(0.0),personAgentRef)
      beamAgentSchedulerRef ! ScheduleTrigger(ActivityStartTrigger(1.0),personAgentRef)
      beamAgentSchedulerRef ! ScheduleTrigger(ActivityEndTrigger(10.0),personAgentRef)
      beamAgentSchedulerRef ! StartSchedule

      EventFilter.info(message = "events-subscriber received actend event!", occurrences = 1)

    }

    it("should be able to route legs"){
      val events: EventsManager = EventsUtils.createEventsManager()
      val eventSubscriber: ActorRef = TestActorRef(new EventsSubscriber(events), "events-subscriber2")
      val actEndDummy = new ActivityEndEvent(0, Id.createPersonId(0), Id.createLinkId(0), Id.create(0, classOf[ActivityFacility]), "dummy")
      val houseIdDummy = Id.create("dummy",classOf[Household])
      agentSimEventsBus.subscribe(eventSubscriber,ActivityEndEvent.EVENT_TYPE)

      val plan = PopulationUtils.getFactory.createPlan()
      val homeActivity = PopulationUtils.createActivityFromLinkId("home", Id.createLinkId(1))
      val workActivity = PopulationUtils.createActivityFromLinkId("work", Id.createLinkId(2))
      plan.addActivity(homeActivity)
      plan.addActivity(workActivity)
      plan.addActivity(homeActivity)
//        choiceCalculator = { (trips: Vector[BeamTrip], weights: Vector[Double] ) => trips.head }, currentVehicle = None)

      val personAgentRef = TestFSMRef(new PersonAgent(services, Id.create("dummyAgent", classOf[PersonAgent]), houseIdDummy, plan, Id.create("dummyBody", classOf[Vehicle]), PersonData()))
      val beamAgentSchedulerRef = TestActorRef[BeamAgentScheduler](SchedulerProps(stopTick = 12.0, maxWindow = 10.0))

      beamAgentSchedulerRef ! ScheduleTrigger(InitializeTrigger(0.0),personAgentRef)
      beamAgentSchedulerRef ! ScheduleTrigger(ActivityStartTrigger(1.0),personAgentRef)
      beamAgentSchedulerRef ! ScheduleTrigger(ActivityEndTrigger(10.0),personAgentRef)
      beamAgentSchedulerRef ! StartSchedule
    }

    it("should demonstrate a simple complete daily activity pattern")(pending)
  }

}

