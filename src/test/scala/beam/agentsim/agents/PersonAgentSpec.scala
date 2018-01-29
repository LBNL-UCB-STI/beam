package beam.agentsim.agents

import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{EventFilter, ImplicitSender, TestActorRef, TestFSMRef, TestKit}
import akka.util.Timeout
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.agents.RideHailingManager.{RideHailingInquiry, RideHailingInquiryResponse}
import beam.agentsim.agents.household.HouseholdActor.{AttributesOfIndividual, HouseholdActor}
import beam.agentsim.agents.modalBehaviors.ModeChoiceCalculator
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.events.SpaceTime
import beam.agentsim.scheduler.BeamAgentScheduler
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger, SchedulerProps, StartSchedule}
import beam.router.BeamRouter.{RoutingRequest, RoutingResponse}
import beam.router.Modes.BeamMode
import beam.router.RoutingModel.{BeamLeg, BeamPath, EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.router.r5.NetworkCoordinator
import beam.sim.BeamServices
import beam.sim.common.GeoUtilsImpl
import beam.sim.config.BeamConfig
import beam.utils.BeamConfigUtils
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.events.ActivityEndEvent
import org.matsim.api.core.v01.events.handler.ActivityEndEventHandler
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.config.ConfigUtils
import org.matsim.core.events.EventsManagerImpl
import org.matsim.core.population.PopulationUtils
import org.matsim.facilities.ActivityFacility
import org.matsim.households.{Household, HouseholdImpl}
import org.matsim.vehicles.{Vehicle, VehicleUtils}
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FunSpecLike}

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._

/**
  * Created by sfeygin on 2/7/17.
  */
class PersonAgentSpec extends TestKit(ActorSystem("testsystem", ConfigFactory.parseString(
  """
  akka.loggers = ["akka.testkit.TestEventListener"]
  akka.log-dead-letters = 10
  akka.actor.debug.fsm = true
  """).withFallback(BeamConfigUtils.parseFileSubstitutingInputDirectory("test/input/beamville/beam.conf").resolve()))) with FunSpecLike
  with BeforeAndAfterAll with MockitoSugar with ImplicitSender {

  private implicit val timeout = Timeout(60, TimeUnit.SECONDS)
  val config = BeamConfig(system.settings.config)
  val eventsManager = new EventsManagerImpl()
  val vehicles = TrieMap[Id[Vehicle], BeamVehicle]()
  val personRefs = TrieMap[Id[Person], ActorRef]()
  val services: BeamServices = {
    val theServices = mock[BeamServices]
    when(theServices.beamConfig).thenReturn(config)
    when(theServices.vehicles).thenReturn(vehicles)
    when(theServices.personRefs).thenReturn(personRefs)
    val geo = new GeoUtilsImpl(theServices)
    when(theServices.geo).thenReturn(geo)
    theServices
  }
  val modeChoiceCalculatorFactory = () => new ModeChoiceCalculator {
    override def apply(alternatives: Seq[EmbodiedBeamTrip], extraAttributes: Option[AttributesOfIndividual]): EmbodiedBeamTrip = alternatives.head
    override val beamServices: BeamServices = services
  }
  private val networkCoordinator = new NetworkCoordinator(config, VehicleUtils.createVehiclesContainer())
  networkCoordinator.loadNetwork()

  describe("A PersonAgent FSM") {

    it("should allow scheduler to set the first activity") {
      val scheduler = TestActorRef[BeamAgentScheduler](SchedulerProps(config, stopTick = 11.0, maxWindow = 10.0))
      val household = new HouseholdImpl(Id.create("dummy", classOf[Household]))
      val homeActivity = PopulationUtils.createActivityFromLinkId("home", Id.createLinkId(1))
      homeActivity.setStartTime(1.0)
      homeActivity.setEndTime(10.0)
      val plan = PopulationUtils.getFactory.createPlan()
      plan.addActivity(homeActivity)
      val attributesOfIndividual = AttributesOfIndividual(household,vehicles.map({case(vid,veh)=>(Id.createVehicleId(vid),veh.matSimVehicle)}).toMap)
      val personAgentRef = TestFSMRef(new PersonAgent(scheduler, services, modeChoiceCalculatorFactory(), networkCoordinator.transportNetwork, self, self, eventsManager, Id.create("dummyAgent", classOf[PersonAgent]), plan, Id.create("dummyBody", classOf[Vehicle]), attributesOfIndividual, PersonData()))

      watch(personAgentRef)
      scheduler ! ScheduleTrigger(InitializeTrigger(0.0), personAgentRef)
      scheduler ! StartSchedule(0)
      expectTerminated(personAgentRef)
      expectMsg(CompletionNotice(0, Vector()))
    }

    it("should publish events that can be received by a MATSim EventsManager") {
      within(10 seconds) {
        val household = new HouseholdImpl(Id.create("dummy", classOf[Household]))
        eventsManager.addHandler(new ActivityEndEventHandler {
          override def handleEvent(event: ActivityEndEvent): Unit = {
            system.log.error("events-subscriber received actend event!")
          }
        })

        val population = PopulationUtils.createPopulation(ConfigUtils.createConfig())
        val person = PopulationUtils.getFactory.createPerson(Id.createPersonId("dummyAgent"))
        val plan = PopulationUtils.getFactory.createPlan()
        val homeActivity = PopulationUtils.createActivityFromLinkId("home", Id.createLinkId(1))
        homeActivity.setEndTime(28800) // 8:00:00 AM
        plan.addActivity(homeActivity)
        val workActivity = PopulationUtils.createActivityFromLinkId("work", Id.createLinkId(2))
        workActivity.setEndTime(61200) //5:00:00 PM
        plan.addActivity(workActivity)
        person.addPlan(plan)
        population.addPerson(person)

        val scheduler = TestActorRef[BeamAgentScheduler](SchedulerProps(config, stopTick = 1000000.0, maxWindow = 10.0))

        val householdActor = TestActorRef[HouseholdActor](new HouseholdActor(services, modeChoiceCalculatorFactory, scheduler, networkCoordinator.transportNetwork, self, self, eventsManager, population, household.getId, household, Map(), Vector(person), new Coord(0.0,0.0)))
        val personActor = householdActor.getSingleChild(person.getId.toString)

        // We want an ActivityEndEvent from this agent, but we have to help him a bit..
        EventFilter.error(message = "events-subscriber received actend event!", occurrences = 1) intercept {
          scheduler ! StartSchedule(0)

          // The agent will ask for a route, and we provide it.
          expectMsgType[RoutingRequest]
          personActor ! RoutingResponse(Vector(EmbodiedBeamTrip(Vector(EmbodiedBeamLeg(BeamLeg(28800, BeamMode.WALK, 100, BeamPath(Vector(1,2), None, SpaceTime(0.0, 0.0, 28800), SpaceTime(1.0, 1.0, 28900), 1000.0)), Id.createVehicleId("body-something"), true, None, BigDecimal(0), true)))))

          // The agent will ask for a ride, and we will answer.
          val inquiry = expectMsgType[RideHailingInquiry]
          personActor ! RideHailingInquiryResponse(inquiry.inquiryId, Nil, None)
        }
      }
    }

    // Finishing this test requires giving the agent a mock router,
    // a household,
    // and verifying that the expected events are thrown.
    ignore("should demonstrate a simple complete daily activity pattern") {
      within(10 seconds) {
        val scheduler = TestActorRef[BeamAgentScheduler](SchedulerProps(config, stopTick = 200.0, maxWindow = 10.0))

        val actEndDummy = new ActivityEndEvent(0, Id.createPersonId(0), Id.createLinkId(0), Id.create(0, classOf[ActivityFacility]), "dummy")
        val household = new HouseholdImpl(Id.create("dummy", classOf[Household]))

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

        val attributesOfIndividual = AttributesOfIndividual(household,vehicles.map({case(vid,veh)=>(Id.createVehicleId(vid),veh.matSimVehicle)}).toMap)
        val personAgentRef = TestFSMRef(new PersonAgent(scheduler, services, modeChoiceCalculatorFactory(), networkCoordinator.transportNetwork, self, self, eventsManager, Id.create("dummyAgent", classOf[PersonAgent]), plan, Id.create("dummyBody", classOf[Vehicle]), attributesOfIndividual, PersonData()))

        watch(personAgentRef)

        scheduler ! ScheduleTrigger(InitializeTrigger(0.0), personAgentRef)
        scheduler ! StartSchedule(0)
        expectTerminated(personAgentRef)
      }
    }

  }

  override def afterAll: Unit = {
    shutdown()
  }

}

