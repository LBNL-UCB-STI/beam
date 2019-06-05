package beam.agentsim.agents

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import akka.util.Timeout
import beam.agentsim.agents.PersonTestUtil._
import beam.agentsim.agents.TransitDriverAgent.createAgentIdFromVehicleId
import beam.agentsim.agents.household.HouseholdActor.HouseholdActor
import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.{BeamVehicle, _}
import beam.agentsim.events._
import beam.agentsim.infrastructure.ZonalParkingManager
import beam.agentsim.scheduler.BeamAgentScheduler
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger, SchedulerProps, StartSchedule}
import beam.router.BeamRouter._
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.WALK_TRANSIT
import beam.router.model.RoutingModel.TransitStopsInfo
import beam.router.model.{EmbodiedBeamLeg, _}
import beam.router.osm.TollCalculator
import beam.router.{BeamSkimmer, RouteHistory, TravelTimeObserved}
import beam.sim.common.GeoUtilsImpl
import beam.sim.population.AttributesOfIndividual
import beam.sim.{BeamHelper, BeamServices}
import beam.utils.TestConfigUtils.testConfig
import beam.utils.{NetworkHelper, StuckFinder}
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.events._
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.population.{Activity, Person}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.api.experimental.events.{EventsManager, TeleportationArrivalEvent}
import org.matsim.core.api.internal.HasPersonId
import org.matsim.core.config.ConfigUtils
import org.matsim.core.events.EventsManagerImpl
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.population.PopulationUtils
import org.matsim.core.population.routes.RouteUtils
import org.matsim.households.{Household, HouseholdsFactoryImpl}
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FunSpecLike}

import scala.collection.mutable.ListBuffer
import scala.collection.{mutable, JavaConverters}
import scala.concurrent.Await
import scala.concurrent.duration._

class PersonAndTransitDriverSpec
    extends TestKit(
      ActorSystem(
        name = "PersonAndTransitDriverSpec",
        config = ConfigFactory
          .parseString(
            """
        akka.log-dead-letters = 10
        akka.actor.debug.fsm = true
        akka.loglevel = debug
        """
          )
          .withFallback(testConfig("test/input/beamville/beam.conf").resolve())
      )
    )
    with FunSpecLike
    with BeforeAndAfterAll
    with MockitoSugar
    with BeamHelper
    with ImplicitSender {

  private implicit val timeout: Timeout = Timeout(60, TimeUnit.SECONDS)
  private val executionConfig: BeamExecutionConfig = setupBeamWithConfig(system.settings.config)
  private lazy val beamConfig = executionConfig.beamConfig
  private lazy val beamScenario = loadScenario(beamConfig)
  private lazy val matsimScenario = buildScenarioFromMatsimConfig(executionConfig.matsimConfig, beamScenario)
  private val injector = buildInjector(system.settings.config, matsimScenario, beamScenario)
  private lazy val beamSvc = buildBeamServices(injector, matsimScenario)
  private lazy val tollCalculator = injector.getInstance(classOf[TollCalculator])
  private lazy val parkingManager = system.actorOf(
    ZonalParkingManager.props(beamConfig, beamScenario.tazTreeMap, beamSvc.geo, beamSvc.beamRouter),
    "ParkingManager"
  )
  private lazy val networkHelper = injector.getInstance(classOf[NetworkHelper])
  private val householdsFactory: HouseholdsFactoryImpl = new HouseholdsFactoryImpl()
  private lazy val modeChoiceCalculator = new ModeChoiceCalculator {
    override def apply(
      alternatives: IndexedSeq[EmbodiedBeamTrip],
      attributesOfIndividual: AttributesOfIndividual,
      destinationActivity: Option[Activity]
    ): Option[EmbodiedBeamTrip] =
      Some(alternatives.head)

    override val beamServices: BeamServices = beamSvc

    override def utilityOf(
      alternative: EmbodiedBeamTrip,
      attributesOfIndividual: AttributesOfIndividual,
      destinationActivity: Option[Activity]
    ): Double = 0.0

    override def utilityOf(mode: BeamMode, cost: Double, time: Double, numTransfers: Int): Double = 0D

    override def computeAllDayUtility(
      trips: ListBuffer[EmbodiedBeamTrip],
      person: Person,
      attributesOfIndividual: AttributesOfIndividual
    ): Double = 0.0
  }

  describe("A PersonAgent") {

    val hoseHoldDummyId = Id.create("dummy", classOf[Household])

    it("should know how to take a walk_transit trip when it's already in its plan") {
      val busId = Id.createVehicleId("bus:B3-WEST-1-175")
      val tramId = Id.createVehicleId("train:R2-SOUTH-1-93")

      val busEvents = new TestProbe(system)
      val tramEvents = new TestProbe(system)
      val personEvents = new TestProbe(system)
      val otherEvents = new TestProbe(system)
      val agencyEvents = new TestProbe(system)

      val eventsManager: EventsManager = new EventsManagerImpl()
      eventsManager.addHandler(
        new BasicEventHandler {
          override def handleEvent(event: Event): Unit = {
            event match {
              case personEvent: HasPersonId if personEvent.getPersonId.toString == busId.toString =>
                busEvents.ref ! event
              case event: HasPersonId if event.getPersonId.toString == tramId.toString =>
                tramEvents.ref ! event
              case personEvent: HasPersonId if personEvent.getPersonId.toString == "dummyAgent" =>
                personEvents.ref ! event
              case pathTraversalEvent: PathTraversalEvent if pathTraversalEvent.vehicleId.toString == busId.toString =>
                busEvents.ref ! event
              case pathTraversalEvent: PathTraversalEvent if pathTraversalEvent.vehicleId.toString == tramId.toString =>
                tramEvents.ref ! event
              case pathTraversalEvent: PathTraversalEvent
                  if pathTraversalEvent.vehicleId.toString == "body-dummyAgent" =>
                personEvents.ref ! event
              case agencyRevenueEvent: AgencyRevenueEvent =>
                agencyEvents.ref ! event
              case _ =>
                otherEvents.ref ! event
            }
          }
        }
      )

      val bus = new BeamVehicle(
        id = busId,
        powerTrain = new Powertrain(0.0),
        beamVehicleType = beamScenario.vehicleTypes(Id.create("Car", classOf[BeamVehicleType]))
      )
      val tram = new BeamVehicle(
        id = tramId,
        powerTrain = new Powertrain(0.0),
        beamVehicleType = beamScenario.vehicleTypes(Id.create("Car", classOf[BeamVehicleType]))
      )

      val busLeg = EmbodiedBeamLeg(
        BeamLeg(
          startTime = 28800,
          mode = BeamMode.BUS,
          duration = 600,
          travelPath = BeamPath(
            Vector(),
            Vector(),
            Some(TransitStopsInfo(1, busId, 2)),
            SpaceTime(beamSvc.geo.utm2Wgs(new Coord(166321.9, 1568.87)), 28800),
            SpaceTime(beamSvc.geo.utm2Wgs(new Coord(167138.4, 1117)), 29400),
            1.0
          )
        ),
        beamVehicleId = busId,
        Id.create("TRANSIT-TYPE-DEFAULT", classOf[BeamVehicleType]),
        asDriver = false,
        cost = 2.75,
        unbecomeDriverOnCompletion = false
      )
      val busLeg2 = EmbodiedBeamLeg(
        beamLeg = BeamLeg(
          startTime = 29400,
          mode = BeamMode.BUS,
          duration = 600,
          travelPath = BeamPath(
            Vector(),
            Vector(),
            Some(TransitStopsInfo(2, busId, 3)),
            SpaceTime(beamSvc.geo.utm2Wgs(new Coord(167138.4, 1117)), 29400),
            SpaceTime(beamSvc.geo.utm2Wgs(new Coord(180000.4, 1200)), 30000),
            1.0
          )
        ),
        beamVehicleId = busId,
        Id.create("TRANSIT-TYPE-DEFAULT", classOf[BeamVehicleType]),
        asDriver = false,
        cost = 0.0,
        unbecomeDriverOnCompletion = false
      )
      val tramLeg = EmbodiedBeamLeg(
        beamLeg = BeamLeg(
          startTime = 30000,
          mode = BeamMode.TRAM,
          duration = 600,
          travelPath = BeamPath(
            linkIds = Vector(),
            linkTravelTime = Vector(),
            transitStops = Some(TransitStopsInfo(3, tramId, 4)),
            startPoint = SpaceTime(beamSvc.geo.utm2Wgs(new Coord(180000.4, 1200)), 30000),
            endPoint = SpaceTime(beamSvc.geo.utm2Wgs(new Coord(190000.4, 1300)), 30600),
            distanceInM = 1.0
          )
        ),
        beamVehicleId = tramId,
        Id.create("TRANSIT-TYPE-DEFAULT", classOf[BeamVehicleType]),
        asDriver = false,
        cost = 0.0,
        unbecomeDriverOnCompletion = false
      )

      val scheduler = TestActorRef[BeamAgentScheduler](
        SchedulerProps(
          beamConfig,
          stopTick = 31001,
          maxWindow = 31001, // As a kind of stress test, let everything happen simultaneously
          new StuckFinder(beamConfig.beam.debug.stuckAgentDetection)
        )
      )

      val busDriverProps = Props(
        new TransitDriverAgent(
          scheduler = scheduler,
          beamScenario,
          transportNetwork = beamScenario.transportNetwork,
          tollCalculator = tollCalculator,
          eventsManager = eventsManager,
          parkingManager = parkingManager,
          transitDriverId = Id.create(busId.toString, classOf[TransitDriverAgent]),
          vehicle = bus,
          Array(busLeg.beamLeg, busLeg2.beamLeg),
          new GeoUtilsImpl(beamConfig),
          networkHelper
        )
      )
      val tramDriverProps = Props(
        new TransitDriverAgent(
          scheduler = scheduler,
          beamScenario,
          transportNetwork = beamScenario.transportNetwork,
          tollCalculator = tollCalculator,
          eventsManager = eventsManager,
          parkingManager = parkingManager,
          transitDriverId = Id.create(tramId.toString, classOf[TransitDriverAgent]),
          vehicle = tram,
          Array(tramLeg.beamLeg),
          new GeoUtilsImpl(beamConfig),
          networkHelper
        )
      )

      val iteration = TestActorRef(
        Props(new Actor() {
          context.actorOf(
            Props(new Actor() {
              context.actorOf(busDriverProps, "TransitDriverAgent-" + busId.toString)
              context.actorOf(tramDriverProps, "TransitDriverAgent-" + tramId.toString)

              override def receive: Receive = Actor.emptyBehavior
            }),
            "transit-system"
          )

          override def receive: Receive = Actor.emptyBehavior
        }),
        "BeamMobsim.iteration"
      )

      val busDriver = Await.result(
        system
          .actorSelection("/user/BeamMobsim.iteration/transit-system/" + createAgentIdFromVehicleId(busId))
          .resolveOne,
        60.seconds
      )
      scheduler ! ScheduleTrigger(InitializeTrigger(0), busDriver)
      val tramDriver = Await.result(
        system
          .actorSelection("/user/BeamMobsim.iteration/transit-system/" + createAgentIdFromVehicleId(tramId))
          .resolveOne,
        60.seconds
      )
      scheduler ! ScheduleTrigger(InitializeTrigger(10000), tramDriver)

      val household = householdsFactory.createHousehold(hoseHoldDummyId)
      val population = PopulationUtils.createPopulation(ConfigUtils.createConfig())
      val person = PopulationUtils.getFactory.createPerson(Id.createPersonId("dummyAgent"))
      putDefaultBeamAttributes(person, Vector(WALK_TRANSIT))
      val plan = PopulationUtils.getFactory.createPlan()
      val homeActivity = PopulationUtils.createActivityFromCoord("home", new Coord(166321.9, 1568.87))
      homeActivity.setEndTime(20000)
      plan.addActivity(homeActivity)
      val leg = PopulationUtils.createLeg("walk_transit")
      val route = RouteUtils.createLinkNetworkRouteImpl(
        Id.createLinkId(1),
        Array[Id[Link]](),
        Id.createLinkId(2)
      )
      leg.setRoute(route)
      plan.addLeg(leg)
      val workActivity = PopulationUtils.createActivityFromCoord("work", new Coord(167138.4, 1117))
      workActivity.setEndTime(61200)
      plan.addActivity(workActivity)
      person.addPlan(plan)
      population.addPerson(person)
      household.setMemberIds(JavaConverters.bufferAsJavaList(mutable.Buffer(person.getId)))

      val householdActor = TestActorRef[HouseholdActor](
        new HouseholdActor(
          beamServices = beamSvc,
          beamScenario,
          modeChoiceCalculatorFactory = _ => modeChoiceCalculator,
          schedulerRef = scheduler,
          transportNetwork = beamScenario.transportNetwork,
          tollCalculator,
          router = self,
          rideHailManager = self,
          parkingManager = parkingManager,
          eventsManager = eventsManager,
          population = population,
          household = household,
          vehicles = Map(),
          homeCoord = new Coord(0.0, 0.0),
          Vector(),
          new RouteHistory(beamConfig),
          mock[BeamSkimmer],
          mock[TravelTimeObserved]
        )
      )

      scheduler ! StartSchedule(0)

      expectMsgType[RoutingRequest]
      lastSender ! RoutingResponse(
        itineraries = Vector(
          EmbodiedBeamTrip(
            legs = Vector(
              EmbodiedBeamLeg(
                beamLeg = BeamLeg(
                  startTime = 20000,
                  mode = BeamMode.WALK,
                  duration = 500,
                  travelPath = BeamPath(
                    linkIds = Vector(),
                    linkTravelTime = Vector(),
                    transitStops = None,
                    startPoint = SpaceTime(beamSvc.geo.utm2Wgs(new Coord(166321.9, 1568.87)), 28800),
                    endPoint = SpaceTime(beamSvc.geo.utm2Wgs(new Coord(167138.4, 1117)), 28800),
                    distanceInM = 1D
                  )
                ),
                beamVehicleId = Id.createVehicleId("body-dummyAgent"),
                Id.create("TRANSIT-TYPE-DEFAULT", classOf[BeamVehicleType]),
                asDriver = true,
                cost = 0.0,
                unbecomeDriverOnCompletion = false
              ),
              busLeg,
              busLeg2,
              tramLeg,
              EmbodiedBeamLeg(
                beamLeg = BeamLeg(
                  startTime = 30600,
                  mode = BeamMode.WALK,
                  duration = 400,
                  travelPath = BeamPath(
                    linkIds = Vector(),
                    linkTravelTime = Vector(),
                    transitStops = None,
                    startPoint = SpaceTime(beamSvc.geo.utm2Wgs(new Coord(167138.4, 1117)), 30600),
                    endPoint = SpaceTime(beamSvc.geo.utm2Wgs(new Coord(167138.4, 1117)), 30600),
                    distanceInM = 1D
                  )
                ),
                beamVehicleId = Id.createVehicleId("body-dummyAgent"),
                Id.create("TRANSIT-TYPE-DEFAULT", classOf[BeamVehicleType]),
                asDriver = true,
                cost = 0.0,
                unbecomeDriverOnCompletion = false
              )
            )
          )
        ),
        requestId = 1
      )

      personEvents.expectMsgType[ModeChoiceEvent]
      personEvents.expectMsgType[ActivityEndEvent]
      personEvents.expectMsgType[PersonDepartureEvent]
      personEvents.expectMsgType[PersonEntersVehicleEvent]
      personEvents.expectMsgType[VehicleEntersTrafficEvent]
      personEvents.expectMsgType[VehicleLeavesTrafficEvent]
      personEvents.expectMsgType[PathTraversalEvent]
      personEvents.expectMsgType[PersonEntersVehicleEvent]
      personEvents.expectMsgType[PersonCostEvent]

      personEvents.expectMsgType[PersonLeavesVehicleEvent]
      personEvents.expectMsgType[PersonEntersVehicleEvent]
      //Fare of second leg is 0.0 so not person cost event is thrown
      personEvents.expectMsgType[PersonLeavesVehicleEvent]
      personEvents.expectMsgType[VehicleEntersTrafficEvent]
      personEvents.expectMsgType[VehicleLeavesTrafficEvent]
      personEvents.expectMsgType[PathTraversalEvent]
      personEvents.expectMsgType[TeleportationArrivalEvent]
      personEvents.expectMsgType[PersonArrivalEvent]
      personEvents.expectMsgType[ActivityStartEvent]

      busEvents.expectMsgType[PersonDepartureEvent]
      busEvents.expectMsgType[PersonEntersVehicleEvent]
      busEvents.expectMsgType[VehicleEntersTrafficEvent]
      busEvents.expectMsgType[VehicleLeavesTrafficEvent]
      busEvents.expectMsgType[PathTraversalEvent]
      busEvents.expectMsgType[VehicleEntersTrafficEvent]
      busEvents.expectMsgType[VehicleLeavesTrafficEvent]
      busEvents.expectMsgType[PathTraversalEvent]

      tramEvents.expectMsgType[PersonDepartureEvent]
      tramEvents.expectMsgType[PersonEntersVehicleEvent]
      tramEvents.expectMsgType[VehicleEntersTrafficEvent]
      tramEvents.expectMsgType[VehicleLeavesTrafficEvent]
      tramEvents.expectMsgType[PathTraversalEvent]

      agencyEvents.expectMsgType[AgencyRevenueEvent]

      otherEvents.expectNoMessage()

      expectMsgType[CompletionNotice]
    }

  }

  override def afterAll: Unit = {
    shutdown()
  }

}
