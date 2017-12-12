package beam.agentsim.agents

import java.io.File
import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{EventFilter, ImplicitSender, TestActorRef, TestFSMRef, TestKit}
import akka.util.Timeout
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.agents.modalBehaviors.ModeChoiceCalculator
import beam.agentsim.scheduler.BeamAgentScheduler
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger, SchedulerProps, StartSchedule}
import beam.sim.BeamServices
import beam.sim.config.BeamConfig
import com.typesafe.config.ConfigFactory
import glokka.Registry
import glokka.Registry.Created
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.ActivityEndEvent
import org.matsim.api.core.v01.events.handler.ActivityEndEventHandler
import org.matsim.core.events.EventsManagerImpl
import org.matsim.core.population.PopulationUtils
import org.matsim.facilities.ActivityFacility
import org.matsim.households.Household
import org.matsim.vehicles.Vehicle
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FunSpecLike, MustMatchers}

/**
  * Created by sfeygin on 2/7/17.
  */
class PersonAgentSpec extends TestKit(ActorSystem("testsystem", ConfigFactory.parseString("""
  akka.loggers = ["akka.testkit.TestEventListener"]
  """).withFallback(ConfigFactory.parseFile(new File("test/input/beamville/beam.conf")).resolve()))) with FunSpecLike with BeforeAndAfterAll with MustMatchers with ImplicitSender with MockitoSugar {

  private implicit val timeout = Timeout(60, TimeUnit.SECONDS)
  val config = BeamConfig(system.settings.config)
  val eventsManager = new EventsManagerImpl()
  val services: BeamServices = {
    val theServices  = mock[BeamServices]
    when(theServices.householdRefs).thenReturn(collection.concurrent.TrieMap[Id[Household], ActorRef]())
    when(theServices.beamConfig).thenReturn(config)
    when(theServices.modeChoiceCalculator).thenReturn(mock[ModeChoiceCalculator])
    theServices
  }

  describe("A PersonAgent FSM") {

    it("should allow scheduler to set the first activity") {
      val houseIdDummy = Id.create("dummy",classOf[Household])
      val homeActivity = PopulationUtils.createActivityFromLinkId("home", Id.createLinkId(1))
      homeActivity.setStartTime(1.0)
      homeActivity.setEndTime(10.0)
      val plan = PopulationUtils.getFactory.createPlan()
      plan.addActivity(homeActivity)

      val personAgentRef = TestFSMRef(new PersonAgent(services, eventsManager, Id.create("dummyAgent", classOf[PersonAgent]), houseIdDummy, plan, Id.create("dummyBody", classOf[Vehicle]),PersonData()))
      val beamAgentSchedulerRef = TestActorRef[BeamAgentScheduler](SchedulerProps(config, stopTick = 11.0, maxWindow = 10.0))

      watch(personAgentRef)
      beamAgentSchedulerRef ! ScheduleTrigger(InitializeTrigger(0.0),personAgentRef)
      beamAgentSchedulerRef ! StartSchedule(0)
      expectTerminated(personAgentRef)
      expectMsg(CompletionNotice(0L))
    }

    it("should be able to be registered in registry") {
      val registry = Registry.start(this.system, "actor-registry")
      val homeActivity = PopulationUtils.createActivityFromLinkId("home", Id.createLinkId(1))
      val name = "0"
      val plan = PopulationUtils.getFactory.createPlan()
      plan.addActivity(homeActivity)
      val householdId  =  Id.create("dummyHousehold", classOf[Household])
      val bodyVehicle = Id.create("dummyBody", classOf[Vehicle])
      val props = PersonAgent.props(services, eventsManager, Id.createPersonId(name), householdId, plan, bodyVehicle)
      registry ! Registry.Register(name, props)
      val ok = expectMsgType[Created]
      ok.name mustEqual name
    }

    it("should publish events that can be received by a MATSim EventsManager") {
      val houseIdDummy = Id.create("dummy",classOf[Household])
      eventsManager.addHandler(new ActivityEndEventHandler {
        override def handleEvent(event: ActivityEndEvent): Unit = {
          system.log.error("events-subscriber received actend event!")
        }
        override def reset(iteration: Int): Unit = {}
      })

      val plan = PopulationUtils.getFactory.createPlan()
      val homeActivity = PopulationUtils.createActivityFromLinkId("home", Id.createLinkId(1))
      homeActivity.setEndTime(28800)  // 8:00:00 AM
      plan.addActivity(homeActivity)
      val workActivity = PopulationUtils.createActivityFromLinkId("work", Id.createLinkId(2))
      workActivity.setEndTime(61200) //5:00:00 PM
      plan.addActivity(workActivity)

      val personAgentRef = TestFSMRef(new PersonAgent(services, eventsManager, Id.create("dummyAgent", classOf[PersonAgent]), houseIdDummy, plan, Id.create("dummyBody", classOf[Vehicle]), PersonData()))
      val beamAgentSchedulerRef = TestActorRef[BeamAgentScheduler](SchedulerProps(config, stopTick = 1000000.0, maxWindow = 10.0))
      beamAgentSchedulerRef ! ScheduleTrigger(InitializeTrigger(0.0), personAgentRef)

      EventFilter.error(message = "events-subscriber received actend event!", occurrences = 1) intercept {
        beamAgentSchedulerRef ! StartSchedule(0)
      }
    }

    it("should be able to route legs"){
      val actEndDummy = new ActivityEndEvent(0, Id.createPersonId(0), Id.createLinkId(0), Id.create(0, classOf[ActivityFacility]), "dummy")
      val houseIdDummy = Id.create("dummy",classOf[Household])

      val plan = PopulationUtils.getFactory.createPlan()
      val homeActivity = PopulationUtils.createActivityFromLinkId("home", Id.createLinkId(1))
      homeActivity.setStartTime(0.0)
      homeActivity.setEndTime(30.0)
      val workActivity = PopulationUtils.createActivityFromLinkId("work", Id.createLinkId(2))
      workActivity.setStartTime(40.0)
      workActivity.setEndTime(70.0)
      val backHomeActivity = PopulationUtils.createActivityFromLinkId("home", Id.createLinkId(1))
      backHomeActivity.setStartTime(80.0)
      backHomeActivity.setEndTime(100.0)

      plan.addActivity(homeActivity)
      plan.addActivity(workActivity)
      plan.addActivity(backHomeActivity)

      val personAgentRef = TestFSMRef(new PersonAgent(services, eventsManager, Id.create("dummyAgent", classOf[PersonAgent]), houseIdDummy, plan, Id.create("dummyBody", classOf[Vehicle]), PersonData()))
      val beamAgentSchedulerRef = TestActorRef[BeamAgentScheduler](SchedulerProps(config, stopTick = 200.0, maxWindow = 10.0))

      beamAgentSchedulerRef ! ScheduleTrigger(InitializeTrigger(0.0),personAgentRef)
      beamAgentSchedulerRef ! StartSchedule(0)
    }

    // TODO
    //it("should demonstrate a simple complete daily activity pattern")(pending)
  }

  override def afterAll: Unit = {
    shutdown()
  }

}

