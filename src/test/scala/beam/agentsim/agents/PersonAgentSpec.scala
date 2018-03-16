package beam.agentsim.agents

import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{ImplicitSender, TestActorRef, TestFSMRef, TestKit}
import akka.util.Timeout
import beam.agentsim.agents.rideHail.RideHailingManager.{RideHailingInquiry, RideHailingInquiryResponse}
import beam.agentsim.agents.household.HouseholdActor.HouseholdActor
import beam.agentsim.agents.modalBehaviors.ModeChoiceCalculator
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.agents.vehicles.BeamVehicleType.Car
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.events.{ModeChoiceEvent, PathTraversalEvent, SpaceTime}
import beam.agentsim.scheduler.BeamAgentScheduler
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger, SchedulerProps, StartSchedule}
import beam.router.BeamRouter.{EmbodyWithCurrentTravelTime, RoutingRequest, RoutingResponse}
import beam.router.Modes
import beam.router.Modes.BeamMode
import beam.router.RoutingModel.{BeamLeg, BeamPath, EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.router.r5.NetworkCoordinator
import beam.sim.BeamServices
import beam.sim.common.GeoUtilsImpl
import beam.sim.config.BeamConfig
import beam.utils.BeamConfigUtils
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.events._
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.api.experimental.events.TeleportationArrivalEvent
import org.matsim.core.config.ConfigUtils
import org.matsim.core.events.EventsManagerImpl
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.population.PopulationUtils
import org.matsim.core.population.routes.RouteUtils
import org.matsim.households.{Household, HouseholdsFactoryImpl}
import org.matsim.vehicles._
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FunSpecLike}

import scala.collection.{JavaConverters, mutable}
import scala.collection.concurrent.TrieMap

/**
  * Created by sfeygin on 2/7/17.
  */
class PersonAgentSpec extends TestKit(ActorSystem("testsystem", ConfigFactory.parseString(
  """
  akka.log-dead-letters = 10
  akka.actor.debug.fsm = true
  """).withFallback(BeamConfigUtils.parseFileSubstitutingInputDirectory("test/input/beamville/beam.conf").resolve()))) with FunSpecLike
  with BeforeAndAfterAll with MockitoSugar with ImplicitSender {

  private implicit val timeout = Timeout(60, TimeUnit.SECONDS)
  val config = BeamConfig(system.settings.config)
  val eventsManager = new EventsManagerImpl()
  eventsManager.addHandler(new BasicEventHandler {
    override def handleEvent(event: Event): Unit = {
      self ! event
    }
  })

  val dummyAgentId = Id.createPersonId("dummyAgent")
  val vehicles = TrieMap[Id[Vehicle], BeamVehicle]()
  val personRefs = TrieMap[Id[Person], ActorRef]()
  val householdsFactory:HouseholdsFactoryImpl= new HouseholdsFactoryImpl()

  val services: BeamServices = {
    val theServices = mock[BeamServices]
    when(theServices.beamConfig).thenReturn(config)
    when(theServices.vehicles).thenReturn(vehicles)
    when(theServices.personRefs).thenReturn(personRefs)
    val geo = new GeoUtilsImpl(theServices)
    when(theServices.geo).thenReturn(geo)
    theServices
  }
  val modeChoiceCalculator = new ModeChoiceCalculator {
    override def apply(alternatives: Seq[EmbodiedBeamTrip]): Option[EmbodiedBeamTrip] = Some(alternatives.head)
    override val beamServices: BeamServices = services
    override def utilityOf(alternative: EmbodiedBeamTrip): Double = 0.0
    override def utilityOf(mode: Modes.BeamMode, cost: Double, time: Double, numTransfers: Int): Double = 0.0
  }
  private val networkCoordinator = new NetworkCoordinator(config, VehicleUtils.createVehiclesContainer())
  networkCoordinator.loadNetwork()

  describe("A PersonAgent FSM") {

    it("should allow scheduler to set the first activity") {
      val scheduler = TestActorRef[BeamAgentScheduler](SchedulerProps(config, stopTick = 11.0, maxWindow = 10.0))
      val household = householdsFactory.createHousehold(Id.create("dummy", classOf[Household]))
      val homeActivity = PopulationUtils.createActivityFromLinkId("home", Id.createLinkId(1))
      homeActivity.setStartTime(1.0)
      homeActivity.setEndTime(10.0)
      val plan = PopulationUtils.getFactory.createPlan()
      plan.addActivity(homeActivity)
      val personAgentRef = TestFSMRef(new PersonAgent(scheduler, services, modeChoiceCalculator, networkCoordinator.transportNetwork, self, self, eventsManager, Id.create("dummyAgent", classOf[PersonAgent]), plan, Id.create("dummyBody", classOf[Vehicle])))

      watch(personAgentRef)
      scheduler ! ScheduleTrigger(InitializeTrigger(0.0), personAgentRef)
      scheduler ! StartSchedule(0)
      expectTerminated(personAgentRef)
      expectMsg(CompletionNotice(0, Vector()))
    }

    // Hopefully deterministic test, where we mock a router and give the agent just one option for its trip.
    it("should demonstrate a complete trip, throwing MATSim events") {
      val household = householdsFactory.createHousehold(Id.create("dummy", classOf[Household]))
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
      household.setMemberIds(JavaConverters.bufferAsJavaList(mutable.Buffer(person.getId)))

      val scheduler = TestActorRef[BeamAgentScheduler](SchedulerProps(config, stopTick = 1000000.0, maxWindow = 10.0))

      val householdActor = TestActorRef[HouseholdActor](new HouseholdActor(services, (_) => modeChoiceCalculator, scheduler, networkCoordinator.transportNetwork, self, self, eventsManager, population, household.getId, household, Map(), new Coord(0.0, 0.0)))
      val personActor = householdActor.getSingleChild(person.getId.toString)

      scheduler ! StartSchedule(0)

      // The agent will ask for a route, and we provide it.
      expectMsgType[RoutingRequest]
      personActor ! RoutingResponse(Vector(EmbodiedBeamTrip(Vector(EmbodiedBeamLeg(BeamLeg(28800, BeamMode.WALK, 100, BeamPath(Vector(1, 2), None, SpaceTime(0.0, 0.0, 28800), SpaceTime(1.0, 1.0, 28900), 1000.0)), Id.createVehicleId("body-something"), true, None, BigDecimal(0), true)))))

      // The agent will ask for a ride, and we will answer.
      val inquiry = expectMsgType[RideHailingInquiry]
      personActor ! RideHailingInquiryResponse(inquiry.inquiryId, Nil, None)

      expectMsgType[ModeChoiceEvent]
      expectMsgType[ActivityEndEvent]
      expectMsgType[PersonDepartureEvent]

      expectMsgType[PersonEntersVehicleEvent]
      expectMsgType[VehicleEntersTrafficEvent]
      expectMsgType[LinkLeaveEvent]
      expectMsgType[LinkEnterEvent]
      expectMsgType[VehicleLeavesTrafficEvent]

      expectMsgType[PathTraversalEvent]
      expectMsgType[PersonLeavesVehicleEvent]
      expectMsgType[TeleportationArrivalEvent]

      expectMsgType[PersonArrivalEvent]
      expectMsgType[ActivityStartEvent]

      expectMsgType[CompletionNotice]
    }

    it("should know how to take a trip when it's already in its plan") {
      val vehicleType = new VehicleTypeImpl(Id.create(1, classOf[VehicleType]))
      val vehicleId = Id.createVehicleId(1)
      val vehicle = new VehicleImpl(vehicleId, vehicleType)
      val beamVehicle = new BeamVehicle(new Powertrain(0.0), vehicle, None, Car)
      vehicles.put(vehicleId, beamVehicle)
      val household = householdsFactory.createHousehold(Id.create("dummy", classOf[Household]))
      val population = PopulationUtils.createPopulation(ConfigUtils.createConfig())
      val person = PopulationUtils.getFactory.createPerson(Id.createPersonId("dummyAgent"))
      val plan = PopulationUtils.getFactory.createPlan()
      val homeActivity = PopulationUtils.createActivityFromLinkId("home", Id.createLinkId(1))
      homeActivity.setEndTime(28800) // 8:00:00 AM
      plan.addActivity(homeActivity)
      val leg = PopulationUtils.createLeg("car")
      val route = RouteUtils.createLinkNetworkRouteImpl(Id.createLinkId(1), Array[Id[Link]](), Id.createLinkId(2))
      route.setVehicleId(vehicleId)
      leg.setRoute(route)
      plan.addLeg(leg)
      val workActivity = PopulationUtils.createActivityFromLinkId("work", Id.createLinkId(2))
      workActivity.setEndTime(61200) //5:00:00 PM
      plan.addActivity(workActivity)
      person.addPlan(plan)
      population.addPerson(person)
      household.setMemberIds(JavaConverters.bufferAsJavaList(mutable.Buffer(person.getId)))

      val scheduler = TestActorRef[BeamAgentScheduler](SchedulerProps(config, stopTick = 1000000.0, maxWindow = 10.0))

      val householdActor = TestActorRef[HouseholdActor](new HouseholdActor(services, (_) => modeChoiceCalculator, scheduler, networkCoordinator.transportNetwork, self, self, eventsManager, population, household.getId, household, Map(beamVehicle.getId -> beamVehicle), new Coord(0.0, 0.0)))
      val personActor = householdActor.getSingleChild(person.getId.toString)

      scheduler ! StartSchedule(0)

      // The agent will ask for current travel times for a route it already knows.
      val embodyRequest = expectMsgType[EmbodyWithCurrentTravelTime]
      personActor ! RoutingResponse(Vector(EmbodiedBeamTrip(Vector(EmbodiedBeamLeg(embodyRequest.leg.copy(duration = 1000), Id.createVehicleId("body-something"), true, None, BigDecimal(0), true)))))

      expectMsgType[ModeChoiceEvent]
      expectMsgType[ActivityEndEvent]
      expectMsgType[PersonDepartureEvent]

      expectMsgType[PersonEntersVehicleEvent]
      expectMsgType[VehicleEntersTrafficEvent]
      expectMsgType[LinkLeaveEvent]
      expectMsgType[LinkEnterEvent]
      expectMsgType[VehicleLeavesTrafficEvent]

      expectMsgType[PathTraversalEvent]
      expectMsgType[PersonLeavesVehicleEvent]
      expectMsgType[TeleportationArrivalEvent]

      expectMsgType[PersonArrivalEvent]
      expectMsgType[ActivityStartEvent]

      expectMsgType[CompletionNotice]
    }


  }


  override def afterAll: Unit = {
    shutdown()
  }

}

