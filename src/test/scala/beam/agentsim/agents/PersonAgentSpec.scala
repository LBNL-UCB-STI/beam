package beam.agentsim.agents

import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{EventFilter, ImplicitSender, TestActorRef, TestFSMRef, TestKit}
import akka.util.Timeout
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.agents.modalBehaviors.ModeChoiceCalculator
import beam.agentsim.scheduler.BeamAgentScheduler
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger, SchedulerProps, StartSchedule}
import beam.router.r5.NetworkCoordinator
import beam.sim.BeamServices
import beam.sim.config.BeamConfig
import beam.utils.BeamConfigUtils
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.ActivityEndEvent
import org.matsim.api.core.v01.events.handler.ActivityEndEventHandler
import org.matsim.core.events.EventsManagerImpl
import org.matsim.core.population.PopulationUtils
import org.matsim.facilities.ActivityFacility
import org.matsim.households.Household
import org.matsim.vehicles.{Vehicle, VehicleUtils}
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FunSpecLike}

import scala.concurrent.duration._

/**
  * Created by sfeygin on 2/7/17.
  */
class PersonAgentSpec extends TestKit(ActorSystem("testsystem", ConfigFactory.parseString(
  """
  akka.loggers = ["akka.testkit.TestEventListener"]
  """).withFallback(BeamConfigUtils.parseFileSubstitutingInputDirectory("test/input/beamville/beam.conf").resolve()))) with FunSpecLike
  with BeforeAndAfterAll with MockitoSugar with ImplicitSender {

  private implicit val timeout = Timeout(60, TimeUnit.SECONDS)
  val config = BeamConfig(system.settings.config)
  val eventsManager = new EventsManagerImpl()
  val services: BeamServices = {
    val theServices = mock[BeamServices]
    when(theServices.householdRefs).thenReturn(collection.concurrent.TrieMap[Id[Household], ActorRef]())
    when(theServices.beamConfig).thenReturn(config)
    when(theServices.modeChoiceCalculator).thenReturn(mock[ModeChoiceCalculator])
    theServices
  }
  private val networkCoordinator = new NetworkCoordinator(config, VehicleUtils.createVehiclesContainer())
  networkCoordinator.loadNetwork()

  describe("A PersonAgent FSM") {

    it("should allow scheduler to set the first activity") {
      val houseIdDummy = Id.create("dummy", classOf[Household])
      val homeActivity = PopulationUtils.createActivityFromLinkId("home", Id.createLinkId(1))
      homeActivity.setStartTime(1.0)
      homeActivity.setEndTime(10.0)
      val plan = PopulationUtils.getFactory.createPlan()
      plan.addActivity(homeActivity)

      val personAgentRef = TestFSMRef(new PersonAgent(services, networkCoordinator.transportNetwork, eventsManager, Id.create("dummyAgent", classOf[PersonAgent]), houseIdDummy, plan, Id.create("dummyBody", classOf[Vehicle]), PersonData()))
      val beamAgentSchedulerRef = TestActorRef[BeamAgentScheduler](SchedulerProps(config, stopTick = 11.0, maxWindow = 10.0))

      watch(personAgentRef)
      beamAgentSchedulerRef ! ScheduleTrigger(InitializeTrigger(0.0), personAgentRef)
      beamAgentSchedulerRef ! StartSchedule(0)
      expectTerminated(personAgentRef)
      expectMsg(CompletionNotice(0, Vector()))
    }

    it("should publish events that can be received by a MATSim EventsManager") {
      within(10 seconds) {
        val houseIdDummy = Id.create("dummy", classOf[Household])
        eventsManager.addHandler(new ActivityEndEventHandler {
          override def handleEvent(event: ActivityEndEvent): Unit = {
            system.log.error("events-subscriber received actend event!")
          }
        })

        val plan = PopulationUtils.getFactory.createPlan()
        val homeActivity = PopulationUtils.createActivityFromLinkId("home", Id.createLinkId(1))
        homeActivity.setEndTime(28800) // 8:00:00 AM
        plan.addActivity(homeActivity)
        val workActivity = PopulationUtils.createActivityFromLinkId("work", Id.createLinkId(2))
        workActivity.setEndTime(61200) //5:00:00 PM
        plan.addActivity(workActivity)

        val personAgentRef = TestFSMRef(new PersonAgent(services, networkCoordinator.transportNetwork, eventsManager, Id.create("dummyAgent", classOf[PersonAgent]), houseIdDummy, plan, Id.create("dummyBody", classOf[Vehicle]), PersonData()))
        val beamAgentSchedulerRef = TestActorRef[BeamAgentScheduler](SchedulerProps(config, stopTick = 1000000.0, maxWindow = 10.0))
        beamAgentSchedulerRef ! ScheduleTrigger(InitializeTrigger(0.0), personAgentRef)

        EventFilter.error(message = "events-subscriber received actend event!", occurrences = 1) intercept {
          beamAgentSchedulerRef ! StartSchedule(0)
        }
        // Need to help the agent -- it can't finish its day on its own yet, without a router and such.
        personAgentRef ! Finish
      }
    }

    // Finishing this test requires giving the agent a mock router,
    // and verifying that the expected events are thrown.
    ignore("should demonstrate a simple complete daily activity pattern") {
      within(10 seconds) {
        val actEndDummy = new ActivityEndEvent(0, Id.createPersonId(0), Id.createLinkId(0), Id.create(0, classOf[ActivityFacility]), "dummy")
        val houseIdDummy = Id.create("dummy", classOf[Household])

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

        val personAgentRef = TestFSMRef(new PersonAgent(services, networkCoordinator.transportNetwork, eventsManager, Id.create("dummyAgent", classOf[PersonAgent]), houseIdDummy, plan, Id.create("dummyBody", classOf[Vehicle]), PersonData()))
        watch(personAgentRef)
        val beamAgentSchedulerRef = TestActorRef[BeamAgentScheduler](SchedulerProps(config, stopTick = 200.0, maxWindow = 10.0))

        beamAgentSchedulerRef ! ScheduleTrigger(InitializeTrigger(0.0), personAgentRef)
        beamAgentSchedulerRef ! StartSchedule(0)
        expectTerminated(personAgentRef)
      }
    }

  }

  override def afterAll: Unit = {
    shutdown()
  }

}

