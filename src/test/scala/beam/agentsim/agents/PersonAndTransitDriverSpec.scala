package beam.agentsim.agents

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import akka.util.Timeout
import beam.agentsim.agents.PersonTestUtil._
import beam.agentsim.agents.choice.mode.ModeSubsidy
import beam.agentsim.agents.household.HouseholdActor.HouseholdActor
import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.{BeamVehicle, _}
import beam.agentsim.events._
import beam.agentsim.infrastructure.ParkingManager.ParkingStockAttributes
import beam.agentsim.infrastructure.{TAZTreeMap, ZonalParkingManager}
import beam.agentsim.scheduler.BeamAgentScheduler
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger, SchedulerProps, StartSchedule}
import beam.router.BeamRouter._
import beam.router.Modes.BeamMode
import beam.router.model.RoutingModel.TransitStopsInfo
import beam.router.model.{EmbodiedBeamLeg, _}
import beam.router.osm.TollCalculator
import beam.router.r5.DefaultNetworkCoordinator
import beam.sim.BeamServices
import beam.sim.common.GeoUtilsImpl
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.population.AttributesOfIndividual
import beam.utils.StuckFinder
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.events._
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.api.experimental.events.{EventsManager, TeleportationArrivalEvent}
import org.matsim.core.api.internal.HasPersonId
import org.matsim.core.config.ConfigUtils
import org.matsim.core.controler.MatsimServices
import org.matsim.core.events.EventsManagerImpl
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.population.PopulationUtils
import org.matsim.core.population.routes.RouteUtils
import org.matsim.households.{Household, HouseholdsFactoryImpl}
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FunSpecLike}

import scala.collection.concurrent.TrieMap
import scala.collection.{mutable, JavaConverters}

class PersonAndTransitDriverSpec
    extends TestKit(
      ActorSystem(
        name = "PersonAgentSpec",
        config = ConfigFactory
          .parseString(
            """
        akka.log-dead-letters = 10
        akka.actor.debug.fsm = true
        akka.loglevel = debug
        """
          )
          .withFallback(testConfig("test/input/beamville/beam.conf"))
      )
    )
    with FunSpecLike
    with BeforeAndAfterAll
    with MockitoSugar
    with ImplicitSender {

  private implicit val timeout: Timeout = Timeout(60, TimeUnit.SECONDS)
  private lazy val beamConfig = BeamConfig(system.settings.config)

  private val vehicles = TrieMap[Id[BeamVehicle], BeamVehicle]()
  private val personRefs = TrieMap[Id[Person], ActorRef]()
  private val householdsFactory: HouseholdsFactoryImpl = new HouseholdsFactoryImpl()
  private val tAZTreeMap: TAZTreeMap = BeamServices.getTazTreeMap("test/input/beamville/taz-centers.csv")
  private val tollCalculator = new TollCalculator(beamConfig)

  private lazy val beamSvc: BeamServices = {
    val matsimServices = mock[MatsimServices]

    val theServices = mock[BeamServices](withSettings().stubOnly())
    when(theServices.matsimServices).thenReturn(matsimServices)
    when(theServices.beamConfig).thenReturn(beamConfig)
    when(theServices.vehicles).thenReturn(vehicles)
    when(theServices.personRefs).thenReturn(personRefs)
    when(theServices.tazTreeMap).thenReturn(tAZTreeMap)
    when(theServices.geo).thenReturn(new GeoUtilsImpl(theServices))
    when(theServices.modeSubsidies).thenReturn(ModeSubsidy(Map()))
    theServices
  }

  private lazy val modeChoiceCalculator = new ModeChoiceCalculator {
    override def apply(
      alternatives: IndexedSeq[EmbodiedBeamTrip],
      attributesOfIndividual: AttributesOfIndividual
    ): Option[EmbodiedBeamTrip] =
      Some(alternatives.head)

    override val beamServices: BeamServices = beamSvc

    override def utilityOf(alternative: EmbodiedBeamTrip, attributesOfIndividual: AttributesOfIndividual): Double = 0.0

    override def utilityOf(mode: BeamMode, cost: Double, time: Double, numTransfers: Int): Double = 0D
  }

  private lazy val parkingManager = system.actorOf(
    ZonalParkingManager
      .props(beamSvc, beamSvc.beamRouter, ParkingStockAttributes(100)),
    "ParkingManager"
  )

  private lazy val networkCoordinator = DefaultNetworkCoordinator(beamConfig)

  private val configBuilder = new MatSimBeamConfigBuilder(system.settings.config)
  private val matsimConfig = configBuilder.buildMatSamConf()

  describe("A PersonAgent") {

    val hoseHoldDummyId = Id.create("dummy", classOf[Household])

    it("should know how to take a walk_transit trip when it's already in its plan") {

      val busEvents = new TestProbe(system)
      val tramEvents = new TestProbe(system)
      val personEvents = new TestProbe(system)
      val otherEvents = new TestProbe(system)

      val eventsManager: EventsManager = new EventsManagerImpl()
      eventsManager.addHandler(
        new BasicEventHandler {
          override def handleEvent(event: Event): Unit = {
            event match {
              case personEvent: HasPersonId if personEvent.getPersonId.toString == "my_bus" =>
                busEvents.ref ! event
              case personEvent: HasPersonId if personEvent.getPersonId.toString == "my_tram" =>
                tramEvents.ref ! event
              case personEvent: HasPersonId if personEvent.getPersonId.toString == "dummyAgent" =>
                personEvents.ref ! event
              case pathTraversalEvent: PathTraversalEvent if pathTraversalEvent.getVehicleId == "my_bus" =>
                busEvents.ref ! event
              case pathTraversalEvent: PathTraversalEvent if pathTraversalEvent.getVehicleId == "my_tram" =>
                tramEvents.ref ! event
              case pathTraversalEvent: PathTraversalEvent if pathTraversalEvent.getVehicleId == "body-dummyAgent" =>
                personEvents.ref ! event
              case _ =>
                otherEvents.ref ! event
            }
          }
        }
      )

      val busId = Id.createVehicleId("my_bus")
      val bus = new BeamVehicle(
        id = busId,
        powerTrain = new Powertrain(0.0),
        initialMatsimAttributes = None,
        beamVehicleType = BeamVehicleType.defaultCarBeamVehicleType
      )
      val tramId = Id.createVehicleId("my_tram")
      val tram = new BeamVehicle(
        id = tramId,
        powerTrain = new Powertrain(0.0),
        initialMatsimAttributes = None,
        beamVehicleType = BeamVehicleType.defaultCarBeamVehicleType
      )

      vehicles.put(bus.getId, bus)
      vehicles.put(tram.getId, tram)

      val busLeg = EmbodiedBeamLeg(
        BeamLeg(
          startTime = 28800,
          mode = BeamMode.BUS,
          duration = 600,
          travelPath = BeamPath(
            Vector(),
            Vector(),
            Some(TransitStopsInfo(1, busId, 2)),
            SpaceTime(new Coord(166321.9, 1568.87), 28800),
            SpaceTime(new Coord(167138.4, 1117), 29400),
            1.0
          )
        ),
        beamVehicleId = busId,
        asDriver = false,
        passengerSchedule = None,
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
            SpaceTime(new Coord(167138.4, 1117), 29400),
            SpaceTime(new Coord(180000.4, 1200), 30000),
            1.0
          )
        ),
        beamVehicleId = busId,
        asDriver = false,
        passengerSchedule = None,
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
            startPoint = SpaceTime(new Coord(180000.4, 1200), 30000),
            endPoint = SpaceTime(new Coord(190000.4, 1300), 30600),
            distanceInM = 1.0
          )
        ),
        beamVehicleId = tramId,
        asDriver = false,
        passengerSchedule = None,
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
          beamServices = beamSvc,
          transportNetwork = networkCoordinator.transportNetwork,
          tollCalculator = tollCalculator,
          eventsManager = eventsManager,
          parkingManager = parkingManager,
          transitDriverId = Id.create("my_bus", classOf[TransitDriverAgent]),
          vehicle = bus,
          Array(busLeg.beamLeg, busLeg2.beamLeg)
        )
      )
      val tramDriverProps = Props(
        new TransitDriverAgent(
          scheduler = scheduler,
          beamServices = beamSvc,
          transportNetwork = networkCoordinator.transportNetwork,
          tollCalculator = tollCalculator,
          eventsManager = eventsManager,
          parkingManager = parkingManager,
          transitDriverId = Id.create("my_tram", classOf[TransitDriverAgent]),
          vehicle = tram,
          Array(tramLeg.beamLeg)
        )
      )

      val router = TestActorRef(
        Props(
          new Actor() {
            context.actorOf(busDriverProps, "TransitDriverAgent-my_bus")
            context.actorOf(tramDriverProps, "TransitDriverAgent-my_tram")

            override def receive: Receive = {
              case _ =>
            }
          }
        ),
        "router"
      )

      val busDriver = router.getSingleChild("TransitDriverAgent-my_bus")
      val tramDriver = router.getSingleChild("TransitDriverAgent-my_tram")
      bus.becomeDriver(busDriver)
      tram.becomeDriver(tramDriver)
      scheduler ! ScheduleTrigger(InitializeTrigger(0), busDriver)
      scheduler ! ScheduleTrigger(InitializeTrigger(10000), tramDriver)

      val household = householdsFactory.createHousehold(hoseHoldDummyId)
      val population = PopulationUtils.createPopulation(ConfigUtils.createConfig())
      val person = PopulationUtils.getFactory.createPerson(Id.createPersonId("dummyAgent"))
      putDefaultBeamAttributes(person)
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
          modeChoiceCalculatorFactory = _ => modeChoiceCalculator,
          schedulerRef = scheduler,
          transportNetwork = networkCoordinator.transportNetwork,
          tollCalculator,
          router = self,
          rideHailManager = self,
          parkingManager = parkingManager,
          eventsManager = eventsManager,
          population = population,
          id = household.getId,
          household = household,
          vehicles = Map(),
          homeCoord = new Coord(0.0, 0.0)
        )
      )
      val personActor = householdActor.getSingleChild(person.getId.toString)
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
                    startPoint = SpaceTime(new Coord(166321.9, 1568.87), 28800),
                    endPoint = SpaceTime(new Coord(167138.4, 1117), 28800),
                    distanceInM = 1D
                  )
                ),
                beamVehicleId = Id.createVehicleId("body-dummyAgent"),
                asDriver = true,
                passengerSchedule = None,
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
                    startPoint = SpaceTime(new Coord(167138.4, 1117), 30600),
                    endPoint = SpaceTime(new Coord(167138.4, 1117), 30600),
                    distanceInM = 1D
                  )
                ),
                beamVehicleId = Id.createVehicleId("body-dummyAgent"),
                asDriver = true,
                passengerSchedule = None,
                cost = 0.0,
                unbecomeDriverOnCompletion = false
              )
            )
          )
        ),
        staticRequestId = java.util.UUID.randomUUID()
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
      personEvents.expectMsgType[PersonCostEvent]
      personEvents.expectMsgType[PersonLeavesVehicleEvent]
      personEvents.expectMsgType[PersonEntersVehicleEvent]
      personEvents.expectMsgType[PersonCostEvent]
      personEvents.expectMsgType[PersonCostEvent]
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

      otherEvents.expectNoMessage()

      expectMsgType[CompletionNotice]
    }

  }

  override def beforeAll: Unit = {
    networkCoordinator.loadNetwork()
    networkCoordinator.convertFrequenciesToTrips()
  }

  override def afterAll: Unit = {
    shutdown()
  }

}
