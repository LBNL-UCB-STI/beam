package beam.agentsim.agents

import java.util.concurrent.TimeUnit

import akka.actor.{ActorSystem, Props}
import akka.pattern.{ask, pipe}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import akka.util.Timeout
import beam.agentsim.Resource.{Boarded, NotAvailable, NotifyVehicleIdle, TryToBoardVehicle}
import beam.agentsim.agents.PersonTestUtil._
import beam.agentsim.agents.choice.mode.ModeIncentive
import beam.agentsim.agents.choice.mode.ModeIncentive.Incentive
import beam.agentsim.agents.household.HouseholdActor.{
  HouseholdActor,
  MobilityStatusInquiry,
  MobilityStatusResponse,
  ReleaseVehicle
}
import beam.agentsim.agents.modalbehaviors.DrivesVehicle.{ActualVehicle, Token}
import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.{BeamVehicle, _}
import beam.agentsim.events._
import beam.agentsim.infrastructure.{ParkingInquiry, ParkingInquiryResponse}
import beam.agentsim.infrastructure.TrivialParkingManager
import beam.agentsim.infrastructure.taz.TAZTreeMap
import beam.agentsim.scheduler.BeamAgentScheduler
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, SchedulerProps, StartSchedule}
import beam.router.BeamRouter._
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{CAR, WALK}
import beam.router.model.{EmbodiedBeamLeg, _}
import beam.router.osm.TollCalculator
import beam.router.r5.DefaultNetworkCoordinator
import beam.router.{BeamSkimmer, RouteHistory}
import beam.sim.BeamServices
import beam.sim.common.GeoUtilsImpl
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.population.AttributesOfIndividual
import beam.utils.TestConfigUtils.testConfig
import beam.utils.{NetworkHelperImpl, StuckFinder}
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.events._
import org.matsim.api.core.v01.population.{Activity, Person}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.api.experimental.events.TeleportationArrivalEvent
import org.matsim.core.api.internal.HasPersonId
import org.matsim.core.config.ConfigUtils
import org.matsim.core.controler.MatsimServices
import org.matsim.core.events.EventsManagerImpl
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.population.PopulationUtils
import org.matsim.core.population.routes.RouteUtils
import org.matsim.core.scenario.ScenarioUtils
import org.matsim.households.{Household, HouseholdsFactoryImpl}
import org.matsim.vehicles._
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FunSpecLike}

import scala.collection.mutable.ListBuffer
import scala.collection.{mutable, JavaConverters}
import scala.concurrent.ExecutionContext

class PersonWithVehicleSharingSpec
    extends TestKit(
      ActorSystem(
        name = "PersonWithVehicleSharingSpec",
        config = ConfigFactory
          .parseString(
            """
        akka.log-dead-letters = 10
        akka.actor.debug.fsm = true
        akka.loglevel = debug
        akka.test.timefactor = 2
        """
          )
          .withFallback(testConfig("test/input/beamville/beam.conf").resolve())
      )
    )
    with FunSpecLike
    with BeforeAndAfterAll
    with MockitoSugar
    with beam.utils.InjectableMock
    with ImplicitSender {

  private implicit val timeout: Timeout = Timeout(60, TimeUnit.SECONDS)
  private implicit val executionContext: ExecutionContext = system.dispatcher
  lazy val beamConfig = BeamConfig(system.settings.config)

  private val householdsFactory: HouseholdsFactoryImpl = new HouseholdsFactoryImpl()
  private val tAZTreeMap: TAZTreeMap = BeamServices.getTazTreeMap("test/input/beamville/taz-centers.csv")
  private val tollCalculator = new TollCalculator(beamConfig)

  private lazy val networkCoordinator = DefaultNetworkCoordinator(beamConfig)
  private lazy val networkHelper = new NetworkHelperImpl(networkCoordinator.network)

  lazy val beamSvc: BeamServices = {
    val matsimServices = mock[MatsimServices]

    val theServices = mock[BeamServices](withSettings().stubOnly())
    when(theServices.matsimServices).thenReturn(matsimServices)
    when(theServices.beamConfig).thenReturn(beamConfig)
    when(theServices.tazTreeMap).thenReturn(tAZTreeMap)
    when(theServices.geo).thenReturn(new GeoUtilsImpl(beamConfig))
    when(theServices.modeIncentives).thenReturn(ModeIncentive(Map[BeamMode, List[Incentive]]()))
    when(theServices.networkHelper).thenReturn(networkHelper)
    when(theServices.vehicleEnergy).thenReturn(mock[VehicleEnergy])

    theServices
  }

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

    setupInjectableMock(beamConfig, beamSvc)
  }

  private val configBuilder = new MatSimBeamConfigBuilder(system.settings.config)
  private val matsimConfig = configBuilder.buildMatSimConf()

  describe("A PersonAgent") {

    val hoseHoldDummyId = Id.create("dummy", classOf[Household])

    it("should know how to take a car trip when it's already in its plan") {
      val events = TestProbe()
      val eventsManager = new EventsManagerImpl()
      eventsManager.addHandler(
        new BasicEventHandler {
          override def handleEvent(event: Event): Unit = {
            events.ref ! event
          }
        }
      )
      val vehicleId = Id.createVehicleId("car-dummyAgent")
      val household = householdsFactory.createHousehold(hoseHoldDummyId)
      val population = PopulationUtils.createPopulation(ConfigUtils.createConfig())

      val person: Person = createTestPerson(Id.createPersonId("dummyAgent"), vehicleId)
      population.addPerson(person)

      household.setMemberIds(JavaConverters.bufferAsJavaList(mutable.Buffer(person.getId)))
      val scenario = ScenarioUtils.createMutableScenario(matsimConfig)
      scenario.setPopulation(population)
      scenario.setLocked()
      ScenarioUtils.loadScenario(scenario)
      when(beamSvc.matsimServices.getScenario).thenReturn(scenario)

      val scheduler = TestActorRef[BeamAgentScheduler](
        SchedulerProps(
          beamConfig,
          stopTick = 24 * 60 * 60,
          maxWindow = 10,
          new StuckFinder(beamConfig.beam.debug.stuckAgentDetection)
        )
      )
      val parkingManager = system.actorOf(Props(new TrivialParkingManager))

      val mockRouter = TestProbe()
      val mockSharedVehicleFleet = TestProbe()
      val mockRideHailingManager = TestProbe()
      val householdActor = TestActorRef[HouseholdActor](
        Props(
          new HouseholdActor(
            beamSvc,
            _ => modeChoiceCalculator,
            scheduler,
            networkCoordinator.transportNetwork,
            tollCalculator,
            mockRouter.ref,
            mockRideHailingManager.ref,
            parkingManager,
            eventsManager,
            population,
            household,
            Map(),
            new Coord(0.0, 0.0),
            sharedVehicleFleets = Vector(mockSharedVehicleFleet.ref),
            new RouteHistory(beamConfig),
            new BeamSkimmer(beamConfig, beamSvc)
          )
        )
      )

      scheduler ! StartSchedule(0)

      // The agent will ask me for vehicles it can use,
      // since I am the manager of a shared vehicle fleet.
      mockSharedVehicleFleet.expectMsgType[MobilityStatusInquiry]

      // I give it a car to use.
      val vehicle = new BeamVehicle(
        vehicleId,
        new Powertrain(0.0),
        BeamVehicleType.defaultCarBeamVehicleType
      )
      vehicle.manager = Some(mockSharedVehicleFleet.ref)
      (parkingManager ? parkingInquiry(SpaceTime(0.0, 0.0, 28800)))
        .collect {
          case ParkingInquiryResponse(stall, _) =>
            vehicle.useParkingStall(stall)
            MobilityStatusResponse(Vector(ActualVehicle(vehicle)))
        } pipeTo mockSharedVehicleFleet.lastSender

      // The agent will ask for current travel times for a route it already knows.
      val embodyRequest = mockRouter.expectMsgType[EmbodyWithCurrentTravelTime]
      mockRouter.lastSender ! RoutingResponse(
        Vector(
          EmbodiedBeamTrip(
            legs = Vector(
              EmbodiedBeamLeg(
                beamLeg = embodyRequest.leg.copy(
                  duration = 500,
                  travelPath = embodyRequest.leg.travelPath.copy(linkTravelTime = Array(0, 500, 0))
                ),
                beamVehicleId = vehicleId,
                beamVehicleTypeId = vehicle.beamVehicleType.id,
                asDriver = true,
                cost = 0.0,
                unbecomeDriverOnCompletion = true
              )
            )
          )
        ),
        requestId = 1
      )

      events.expectMsgType[ModeChoiceEvent]
      events.expectMsgType[ActivityEndEvent]
      events.expectMsgType[PersonDepartureEvent]

      events.expectMsgType[PersonEntersVehicleEvent]
      events.expectMsgType[VehicleEntersTrafficEvent]
      events.expectMsgType[VehicleLeavesTrafficEvent]
      events.expectMsgType[PathTraversalEvent]

      events.expectMsgType[PersonEntersVehicleEvent]
      events.expectMsgType[LeavingParkingEvent]
      events.expectMsgType[VehicleEntersTrafficEvent]
      events.expectMsgType[LinkLeaveEvent]
      events.expectMsgType[LinkEnterEvent]
      events.expectMsgType[VehicleLeavesTrafficEvent]
      events.expectMsgType[PathTraversalEvent]
      events.expectMsgType[VehicleEntersTrafficEvent]
      events.expectMsgType[LinkLeaveEvent]
      events.expectMsgType[LinkEnterEvent]
      events.expectMsgType[VehicleLeavesTrafficEvent]
      events.expectMsgType[PathTraversalEvent]
      events.expectMsgType[ParkEvent]
      events.expectMsgType[PersonCostEvent]
      events.expectMsgType[PersonLeavesVehicleEvent]

      events.expectMsgType[VehicleEntersTrafficEvent]
      events.expectMsgType[VehicleLeavesTrafficEvent]
      events.expectMsgType[PathTraversalEvent]

      events.expectMsgType[PersonLeavesVehicleEvent]
      events.expectMsgType[TeleportationArrivalEvent]

      events.expectMsgType[PersonArrivalEvent]
      events.expectMsgType[ActivityStartEvent]

      expectMsgType[CompletionNotice]
    }

    it("should not feel compelled to keep driving a shared car for the whole tour") {
      val events = TestProbe()
      val eventsManager = new EventsManagerImpl()
      eventsManager.addHandler(
        new BasicEventHandler {
          override def handleEvent(event: Event): Unit = {
            events.ref ! event
          }
        }
      )
      val vehicleId = Id.createVehicleId("car-dummyAgent")
      val household = householdsFactory.createHousehold(hoseHoldDummyId)
      val population = PopulationUtils.createPopulation(ConfigUtils.createConfig())

      val person: Person =
        createTestPerson(Id.createPersonId("dummyAgent"), vehicleId, withRoute = false, returnTrip = true)
      population.addPerson(person)

      household.setMemberIds(JavaConverters.bufferAsJavaList(mutable.Buffer(person.getId)))
      val scenario = ScenarioUtils.createMutableScenario(matsimConfig)
      scenario.setPopulation(population)
      scenario.setLocked()
      ScenarioUtils.loadScenario(scenario)
      when(beamSvc.matsimServices.getScenario).thenReturn(scenario)

      val scheduler = TestActorRef[BeamAgentScheduler](
        SchedulerProps(
          beamConfig,
          stopTick = 24 * 60 * 60,
          maxWindow = 10,
          new StuckFinder(beamConfig.beam.debug.stuckAgentDetection)
        )
      )
      val parkingManager = system.actorOf(Props(new TrivialParkingManager))

      val mockRouter = TestProbe()
      val mockSharedVehicleFleet = TestProbe()
      val mockRideHailingManager = TestProbe()
      val householdActor = TestActorRef[HouseholdActor](
        Props(
          new HouseholdActor(
            beamSvc,
            _ => modeChoiceCalculator,
            scheduler,
            networkCoordinator.transportNetwork,
            tollCalculator,
            mockRouter.ref,
            mockRideHailingManager.ref,
            parkingManager,
            eventsManager,
            population,
            household,
            Map(),
            new Coord(0.0, 0.0),
            sharedVehicleFleets = Vector(mockSharedVehicleFleet.ref),
            new RouteHistory(beamConfig),
            new BeamSkimmer(beamConfig, beamSvc)
          )
        )
      )

      scheduler ! StartSchedule(0)

      // The agent will ask me for vehicles it can use,
      // since I am the manager of a shared vehicle fleet.
      mockSharedVehicleFleet.expectMsgType[MobilityStatusInquiry]

      // I give it a car to use.
      val vehicle = new BeamVehicle(
        vehicleId,
        new Powertrain(0.0),
        BeamVehicleType.defaultCarBeamVehicleType
      )
      vehicle.manager = Some(mockSharedVehicleFleet.ref)
      (parkingManager ? parkingInquiry(SpaceTime(0.0, 0.0, 28800)))
        .collect {
          case ParkingInquiryResponse(stall, _) =>
            vehicle.setReservedParkingStall(Some(stall))
            vehicle.useParkingStall(stall)
            MobilityStatusResponse(Vector(ActualVehicle(vehicle)))
        } pipeTo mockSharedVehicleFleet.lastSender

      val routingRequest = mockRouter.expectMsgType[RoutingRequest]
      mockRouter.lastSender ! RoutingResponse(
        itineraries = Vector(
          EmbodiedBeamTrip(
            legs = Vector(
              EmbodiedBeamLeg(
                beamLeg = BeamLeg(
                  startTime = 28800,
                  mode = BeamMode.WALK,
                  duration = 50,
                  travelPath = BeamPath(
                    linkIds = Vector(1, 2),
                    linkTravelTime = Vector(50, 50),
                    transitStops = None,
                    startPoint = SpaceTime(0.0, 0.0, 28800),
                    endPoint = SpaceTime(0.01, 0.0, 28950),
                    distanceInM = 1000D
                  )
                ),
                beamVehicleId = Id.createVehicleId("body-dummyAgent"),
                BeamVehicleType.defaultTransitBeamVehicleType.id,
                asDriver = true,
                cost = 0.0,
                unbecomeDriverOnCompletion = false
              ),
              EmbodiedBeamLeg(
                beamLeg = BeamLeg(
                  startTime = 28950,
                  mode = BeamMode.CAR,
                  duration = 50,
                  travelPath = BeamPath(
                    linkIds = Vector(3, 4),
                    linkTravelTime = Vector(50, 50),
                    transitStops = None,
                    startPoint = SpaceTime(0.01, 0.0, 28950),
                    endPoint = SpaceTime(0.01, 0.01, 29000),
                    distanceInM = 1000D
                  )
                ),
                beamVehicleId = vehicle.id,
                BeamVehicleType.defaultTransitBeamVehicleType.id,
                asDriver = true,
                cost = 0.0,
                unbecomeDriverOnCompletion = true
              )
            )
          )
        ),
        requestId = 1
      )

      events.expectMsgType[ModeChoiceEvent]
      events.expectMsgType[ActivityEndEvent]
      events.expectMsgType[PersonDepartureEvent]

      events.expectMsgType[PersonEntersVehicleEvent]
      events.expectMsgType[VehicleEntersTrafficEvent]
      events.expectMsgType[VehicleLeavesTrafficEvent]
      events.expectMsgType[PathTraversalEvent]

      events.expectMsgType[PersonEntersVehicleEvent]
      events.expectMsgType[LeavingParkingEvent]
      events.expectMsgType[VehicleEntersTrafficEvent]
      events.expectMsgType[VehicleLeavesTrafficEvent]
      events.expectMsgType[PathTraversalEvent]
      events.expectMsgType[VehicleEntersTrafficEvent]
      events.expectMsgType[LinkLeaveEvent]
      events.expectMsgType[LinkEnterEvent]
      events.expectMsgType[VehicleLeavesTrafficEvent]
      events.expectMsgType[PathTraversalEvent]
      events.expectMsgType[ParkEvent]
      events.expectMsgType[PersonLeavesVehicleEvent]

      mockSharedVehicleFleet.expectMsgType[NotifyVehicleIdle]
      mockSharedVehicleFleet.expectMsgType[ReleaseVehicle]

      events.expectMsgType[VehicleEntersTrafficEvent]
      events.expectMsgType[VehicleLeavesTrafficEvent]
      events.expectMsgType[PathTraversalEvent]

      events.expectMsgType[PersonLeavesVehicleEvent]
      events.expectMsgType[TeleportationArrivalEvent]

      events.expectMsgType[PersonArrivalEvent]
      events.expectMsgType[ActivityStartEvent]

      // Agent will ask about the car (will not take it for granted that it is there)
      mockSharedVehicleFleet.expectMsgType[MobilityStatusInquiry]
      // I give it a _different_ car to use.
      val vehicle2 = new BeamVehicle(
        vehicleId,
        new Powertrain(0.0),
        BeamVehicleType.defaultCarBeamVehicleType
      )
      vehicle2.manager = Some(mockSharedVehicleFleet.ref)
      (parkingManager ? parkingInquiry(SpaceTime(0.01, 0.01, 61200)))
        .collect {
          case ParkingInquiryResponse(stall, _) =>
            vehicle2.setReservedParkingStall(Some(stall))
            vehicle2.useParkingStall(stall)
            MobilityStatusResponse(Vector(ActualVehicle(vehicle2)))
        } pipeTo mockSharedVehicleFleet.lastSender

      val routingRequest2 = mockRouter.expectMsgType[RoutingRequest]
      mockRouter.lastSender ! RoutingResponse(
        itineraries = Vector(
          EmbodiedBeamTrip(
            legs = Vector(
              EmbodiedBeamLeg(
                beamLeg = BeamLeg(
                  startTime = 61200,
                  mode = BeamMode.CAR,
                  duration = 40,
                  travelPath = BeamPath(
                    linkIds = Vector(4, 3, 2, 1),
                    linkTravelTime = Vector(10, 10, 10, 10),
                    transitStops = None,
                    startPoint = SpaceTime(0.01, 0.01, 61200),
                    endPoint = SpaceTime(0.0, 0.0, 61240),
                    distanceInM = 1000D
                  )
                ),
                beamVehicleId = vehicle2.id,
                vehicle2.beamVehicleType.id,
                asDriver = true,
                cost = 0.0,
                unbecomeDriverOnCompletion = true
              )
            )
          )
        ),
        requestId = 1
      )
      val modeChoiceEvent = events.expectMsgType[ModeChoiceEvent]
      assert(modeChoiceEvent.chosenTrip.tripClassifier == CAR)

      expectMsgType[CompletionNotice]
    }

    it("should replan when the car that was originally offered is taken") {
      val population = PopulationUtils.createPopulation(ConfigUtils.createConfig())
      val mockSharedVehicleFleet = TestProbe()
      val car1 = new BeamVehicle(
        Id.createVehicleId("car-1"),
        new Powertrain(0.0),
        BeamVehicleType.defaultCarBeamVehicleType
      )
      car1.manager = Some(mockSharedVehicleFleet.ref)

      val person1: Person = createTestPerson(Id.createPersonId("dummyAgent"), car1.id)
      population.addPerson(person1)
      val person2: Person = createTestPerson(Id.createPersonId("dummyAgent2"), car1.id, 20)
      population.addPerson(person2)

      val modeChoiceEvents = TestProbe()
      val person1EntersVehicleEvents = TestProbe()
      val person2EntersVehicleEvents = TestProbe()
      val eventsManager = new EventsManagerImpl()
      eventsManager.addHandler(
        new BasicEventHandler {
          override def handleEvent(event: Event): Unit = {
            if (event.isInstanceOf[ModeChoiceEvent]) {
              modeChoiceEvents.ref ! event
            }
            if (event.isInstanceOf[PersonEntersVehicleEvent] &&
                event.asInstanceOf[HasPersonId].getPersonId == person1.getId) {
              person1EntersVehicleEvents.ref ! event
            }
            if (event.isInstanceOf[PersonEntersVehicleEvent] &&
                event.asInstanceOf[HasPersonId].getPersonId == person2.getId) {
              person2EntersVehicleEvents.ref ! event
            }
          }
        }
      )

      val household = householdsFactory.createHousehold(hoseHoldDummyId)

      household.setMemberIds(JavaConverters.bufferAsJavaList(mutable.Buffer(person1.getId, person2.getId)))
      val scenario = ScenarioUtils.createMutableScenario(matsimConfig)
      scenario.setPopulation(population)
      scenario.setLocked()
      ScenarioUtils.loadScenario(scenario)
      when(beamSvc.matsimServices.getScenario).thenReturn(scenario)

      val scheduler = TestActorRef[BeamAgentScheduler](
        SchedulerProps(
          beamConfig,
          stopTick = 24 * 60 * 60,
          maxWindow = 10,
          new StuckFinder(beamConfig.beam.debug.stuckAgentDetection)
        )
      )
      val parkingManager = system.actorOf(Props(new TrivialParkingManager))

      val mockRouter = TestProbe()
      val mockRideHailingManager = TestProbe()

      val householdAgent = TestActorRef[HouseholdActor](
        new HouseholdActor(
          beamSvc,
          _ => modeChoiceCalculator,
          scheduler,
          networkCoordinator.transportNetwork,
          tollCalculator,
          mockRouter.ref,
          mockRideHailingManager.ref,
          parkingManager,
          eventsManager,
          population,
          household,
          Map(),
          new Coord(0.0, 0.0),
          Vector(mockSharedVehicleFleet.ref),
          new RouteHistory(beamConfig),
          new BeamSkimmer(beamConfig, beamSvc)
        )
      )

      scheduler ! StartSchedule(0)

      mockSharedVehicleFleet.expectMsgType[MobilityStatusInquiry]
      (parkingManager ? parkingInquiry(SpaceTime(0.0, 0.0, 28800)))
        .collect {
          case ParkingInquiryResponse(stall, _) =>
            car1.useParkingStall(stall)
            MobilityStatusResponse(Vector(Token(car1.id, car1.manager.get, car1.toStreetVehicle)))
        } pipeTo mockSharedVehicleFleet.lastSender

      mockRouter.expectMsgPF() {
        case EmbodyWithCurrentTravelTime(leg, vehicleId, vehicleTypeId, _) =>
          assert(vehicleId == car1.id, "Agent should ask for route with the car I gave it.")
          val embodiedLeg = EmbodiedBeamLeg(
            beamLeg = leg.copy(
              duration = 500,
              travelPath = leg.travelPath.copy(linkTravelTime = Array(0, 500, 0))
            ),
            beamVehicleId = vehicleId,
            beamVehicleTypeId = vehicleTypeId,
            asDriver = true,
            cost = 0.0,
            unbecomeDriverOnCompletion = true
          )
          mockRouter.lastSender ! RoutingResponse(
            Vector(EmbodiedBeamTrip(Vector(embodiedLeg))),
            requestId = 1
          )
      }

      modeChoiceEvents.expectMsgType[ModeChoiceEvent]

      // body
      person1EntersVehicleEvents.expectMsgType[PersonEntersVehicleEvent]

      mockSharedVehicleFleet.expectMsgType[TryToBoardVehicle]
      mockSharedVehicleFleet.lastSender ! Boarded(car1)

      // car
      person1EntersVehicleEvents.expectMsgType[PersonEntersVehicleEvent]

      mockSharedVehicleFleet.expectMsgType[MobilityStatusInquiry]
      mockSharedVehicleFleet.lastSender ! MobilityStatusResponse(
        Vector(Token(car1.id, car1.manager.get, car1.toStreetVehicle))
      )
      mockRouter.expectMsgPF() {
        case EmbodyWithCurrentTravelTime(leg, vehicleId, vehicleTypeId, _) =>
          assert(vehicleId == car1.id, "Agent should ask for route with the car I gave it.")
          val embodiedLeg = EmbodiedBeamLeg(
            beamLeg = leg.copy(
              duration = 500,
              travelPath = leg.travelPath.copy(linkTravelTime = Array(0, 500, 0))
            ),
            beamVehicleId = vehicleId,
            beamVehicleTypeId = vehicleTypeId,
            asDriver = true,
            cost = 0.0,
            unbecomeDriverOnCompletion = true
          )
          mockRouter.lastSender ! RoutingResponse(
            Vector(EmbodiedBeamTrip(Vector(embodiedLeg))),
            requestId = 1
          )
      }

      modeChoiceEvents.expectMsgType[ModeChoiceEvent]

      // body
      person2EntersVehicleEvents.expectMsgType[PersonEntersVehicleEvent]

      mockSharedVehicleFleet.expectMsgType[TryToBoardVehicle]
      mockSharedVehicleFleet.lastSender ! NotAvailable

      person2EntersVehicleEvents.expectNoMessage()

      mockSharedVehicleFleet.expectMsgPF() {
        case MobilityStatusInquiry(_, SpaceTime(_, 28820), _) =>
      }
      mockSharedVehicleFleet.lastSender ! MobilityStatusResponse(Vector())

      // agent has no car available, so will ask for new route
      mockRouter.expectMsgPF() {
        case RoutingRequest(_, _, _, _, streetVehicles, _, _, _) =>
          val body = streetVehicles.find(_.mode == WALK).get
          val embodiedLeg = EmbodiedBeamLeg(
            beamLeg = BeamLeg(
              28820,
              BeamMode.WALK,
              500,
              BeamPath(Vector(), Vector(), None, SpaceTime(0, 0, 28820), SpaceTime(0, 0, 28820), 0.0)
            ),
            beamVehicleId = body.id,
            beamVehicleTypeId = body.vehicleTypeId,
            asDriver = true,
            cost = 0.0,
            unbecomeDriverOnCompletion = true
          )
          mockRouter.lastSender ! RoutingResponse(
            Vector(EmbodiedBeamTrip(Vector(embodiedLeg))),
            requestId = 1
          )
      }

      expectMsgType[CompletionNotice]
    }

  }

  private def createTestPerson(
    personId: Id[Person],
    vehicleId: Id[Vehicle],
    departureTimeOffset: Int = 0,
    withRoute: Boolean = true,
    returnTrip: Boolean = false,
  ) = {
    val person = PopulationUtils.getFactory.createPerson(personId)
    putDefaultBeamAttributes(person, Vector(CAR, WALK))
    val plan = PopulationUtils.getFactory.createPlan()
    val homeActivity = PopulationUtils.createActivityFromLinkId("home", Id.createLinkId(1))
    homeActivity.setEndTime(28800 + departureTimeOffset) // 8:00:00 AM
    homeActivity.setCoord(new Coord(0.0, 0.0))
    plan.addActivity(homeActivity)
    val leg = PopulationUtils.createLeg("car")
    if (withRoute) {
      val route = RouteUtils.createLinkNetworkRouteImpl(
        Id.createLinkId(0),
        Array(Id.createLinkId(1)),
        Id.createLinkId(2)
      )
      leg.setRoute(route)
    }
    plan.addLeg(leg)
    val workActivity = PopulationUtils.createActivityFromLinkId("work", Id.createLinkId(2))
    workActivity.setEndTime(61200) //5:00:00 PM
    workActivity.setCoord(new Coord(0.01, 0.01))
    plan.addActivity(workActivity)
    if (returnTrip) {
      val leg2 = PopulationUtils.createLeg("car")
      if (withRoute) {
        val route = RouteUtils.createLinkNetworkRouteImpl(
          Id.createLinkId(2),
          Array(Id.createLinkId(1)),
          Id.createLinkId(0)
        )
        leg2.setRoute(route)
      }
      plan.addLeg(leg2)
      val homeActivity2 = PopulationUtils.createActivityFromLinkId("home", Id.createLinkId(1))
      homeActivity2.setCoord(new Coord(0.0, 0.0))
      plan.addActivity(homeActivity2)
    }
    person.addPlan(plan)
    person
  }

  def parkingInquiry(whenWhere: SpaceTime) = ParkingInquiry(
    whenWhere.loc,
    "wherever",
    0.0,
    None,
    0.0
  )

  override def beforeAll: Unit = {
    networkCoordinator.loadNetwork()
    networkCoordinator.convertFrequenciesToTrips()
  }

  override def afterAll: Unit = {
    shutdown()
  }

}
