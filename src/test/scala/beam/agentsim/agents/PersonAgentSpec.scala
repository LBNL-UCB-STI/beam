package beam.agentsim.agents

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.testkit.TestActors.ForwardActor
import akka.testkit.{ImplicitSender, TestActorRef, TestFSMRef, TestKit, TestProbe}
import akka.util.Timeout
import beam.agentsim.agents.PersonAgentSpec.ZERO
import beam.agentsim.agents.household.HouseholdActor.HouseholdActor
import beam.agentsim.agents.modalbehaviors.DrivesVehicle.{NotifyLegEndTrigger, NotifyLegStartTrigger}
import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator
import beam.agentsim.agents.ridehail.{RideHailRequest, RideHailResponse}
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.{BeamVehicle, ReservationRequest, ReservationResponse, ReserveConfirmInfo, _}
import beam.agentsim.events.{ModeChoiceEvent, PathTraversalEvent, SpaceTime}
import beam.agentsim.infrastructure.ParkingManager.ParkingStockAttributes
import beam.agentsim.infrastructure.{TAZTreeMap, ZonalParkingManager}
import beam.agentsim.scheduler.BeamAgentScheduler
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger, SchedulerProps, StartSchedule}
import beam.router.BeamRouter.{RoutingRequest, RoutingResponse}
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.TRANSIT
import beam.router.RoutingModel.{EmbodiedBeamLeg, _}
import beam.router.r5.NetworkCoordinator
import beam.sim.BeamServices
import beam.sim.common.GeoUtilsImpl
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.utils.StuckFinder
import beam.utils.TestConfigUtils.testConfig
import beam.utils.plansampling.PlansSampler
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.events._
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.api.experimental.events.{EventsManager, TeleportationArrivalEvent}
import org.matsim.core.config.ConfigUtils
import org.matsim.core.controler.MatsimServices
import org.matsim.core.events.EventsManagerImpl
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.population.PopulationUtils
import org.matsim.core.population.routes.RouteUtils
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.matsim.households.{Household, HouseholdsFactoryImpl}
import org.matsim.vehicles._
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FunSpecLike}

import scala.collection.concurrent.TrieMap
import scala.collection.{JavaConverters, mutable}
import scala.concurrent.Await

class PersonAgentSpec
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

  private lazy val beamSvc: BeamServices = {
    val matsimServices = mock[MatsimServices]

    val theServices = mock[BeamServices]
    when(theServices.matsimServices).thenReturn(matsimServices)
    when(theServices.beamConfig).thenReturn(beamConfig)
    when(theServices.vehicles).thenReturn(vehicles)
    when(theServices.personRefs).thenReturn(personRefs)
    when(theServices.tazTreeMap).thenReturn(tAZTreeMap)
    when(theServices.geo).thenReturn(new GeoUtilsImpl(theServices))

    theServices
  }

  private lazy val modeChoiceCalculator = new ModeChoiceCalculator {
    override def apply(alternatives: IndexedSeq[EmbodiedBeamTrip]): Option[EmbodiedBeamTrip] =
      Some(alternatives.head)

    override val beamServices: BeamServices = beamSvc

    override def utilityOf(alternative: EmbodiedBeamTrip): Double = 0.0

    override def utilityOf(
      mode: BeamMode,
      cost: BigDecimal,
      time: BigDecimal,
      numTransfers: Int
    ): Double = 0D
  }

  // Mock a transit driver (who has to be a child of a mock router)
  private lazy val transitDriverProps = Props(new ForwardActor(self))

  private val router = system.actorOf(
    Props(
      new Actor() {
        context.actorOf(transitDriverProps, "TransitDriverAgent-my_bus")
        context.actorOf(transitDriverProps, "TransitDriverAgent-my_tram")

        override def receive: Receive = {
          case _ =>
        }
      }
    ),
    "router"
  )

  private lazy val parkingManager = system.actorOf(
    ZonalParkingManager
      .props(beamSvc, beamSvc.beamRouter, ParkingStockAttributes(100)),
    "ParkingManager"
  )

  private val dummyAgentVehicleId = Id.createVehicleId("body-dummyAgent")

  private lazy val networkCoordinator = new NetworkCoordinator(beamConfig)

  private val configBuilder = new MatSimBeamConfigBuilder(system.settings.config)
  private val matsimConfig = configBuilder.buildMatSamConf()

  describe("A PersonAgent") {

    val hoseHoldDummyId = Id.create("dummy", classOf[Household])

    it("should allow scheduler to set the first activity") {
      val eventsManager = new EventsManagerImpl()
      eventsManager.addHandler(
        new BasicEventHandler {
          override def handleEvent(event: Event): Unit = {
            self ! event
          }
        }
      )
      val scheduler =
        TestActorRef[BeamAgentScheduler](
          SchedulerProps(
            beamConfig,
            stopTick = 11,
            maxWindow = 10,
            new StuckFinder(beamConfig.beam.debug.stuckAgentDetection)
          )
        )
      val household = householdsFactory.createHousehold(hoseHoldDummyId)
      val homeActivity = PopulationUtils.createActivityFromLinkId("home", Id.createLinkId(1))
      homeActivity.setStartTime(1.0)
      homeActivity.setEndTime(10.0)
      val plan = PopulationUtils.getFactory.createPlan()
      plan.addActivity(homeActivity)
      val personAgentRef = TestFSMRef(
        new PersonAgent(
          scheduler,
          beamSvc,
          modeChoiceCalculator,
          networkCoordinator.transportNetwork,
          self,
          self,
          eventsManager,
          Id.create("dummyAgent", classOf[PersonAgent]),
          plan,
          Id.create("dummyBody", classOf[Vehicle]),
          parkingManager
        )
      )

      watch(personAgentRef)
      scheduler ! ScheduleTrigger(InitializeTrigger(0), personAgentRef)
      scheduler ! StartSchedule(0)
      expectTerminated(personAgentRef)
      expectMsg(CompletionNotice(0, Vector()))
    }

    // Hopefully deterministic test, where we mock a router and give the agent just one option for its trip.
    // TODO: probably test needs to be updated due to update in rideHailManager

    ignore("should demonstrate a complete trip, throwing MATSim events") {
      val eventsManager = new EventsManagerImpl()
      eventsManager.addHandler(
        new BasicEventHandler {
          override def handleEvent(event: Event): Unit = {
            self ! event
          }
        }
      )

      val scenario = ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]
      val household = householdsFactory.createHousehold(hoseHoldDummyId)
      val population = PopulationUtils.createPopulation(matsimConfig)

      val person = PopulationUtils.getFactory.createPerson(Id.createPersonId("dummyAgent"))
      population.getPersonAttributes.putAttribute(
        person.getId.toString,
        PlansSampler.availableModeString,
        "car,ride_hail,bike,bus,funicular,gondola,cable_car,ferry,tram,transit,rail,subway,tram"
      )
      population.getPersonAttributes
        .putAttribute(person.getId.toString, "valueOfTime", 15.0)
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
      scenario.setPopulation(population)
      scenario.setLocked()
      ScenarioUtils.loadScenario(scenario)
      when(beamSvc.matsimServices.getScenario).thenReturn(scenario)
      val scheduler = TestActorRef[BeamAgentScheduler](
        SchedulerProps(
          beamConfig,
          stopTick = 1000000,
          maxWindow = 10,
          new StuckFinder(beamConfig.beam.debug.stuckAgentDetection)
        )
      )

      val householdActor = TestActorRef[HouseholdActor](
        new HouseholdActor(
          beamSvc,
          _ => modeChoiceCalculator,
          scheduler,
          networkCoordinator.transportNetwork,
          self,
          self,
          parkingManager,
          eventsManager,
          population,
          household.getId,
          household,
          Map(),
          new Coord(0.0, 0.0)
        )
      )
      val personActor = householdActor.getSingleChild(person.getId.toString)

      scheduler ! StartSchedule(0)

      // The agent will ask for a route, and we provide it.
      expectMsgType[RoutingRequest]
      personActor ! RoutingResponse(
        itineraries = Vector(
          EmbodiedBeamTrip(
            legs = Vector(
              EmbodiedBeamLeg(
                beamLeg = BeamLeg(
                  startTime = 28800,
                  mode = BeamMode.WALK,
                  duration = 100,
                  travelPath = BeamPath(
                    linkIds = Vector(1, 2),
                    linkTravelTime = Vector(10, 10), // TODO FIXME
                    transitStops = None,
                    startPoint = SpaceTime(0.0, 0.0, 28800),
                    endPoint = SpaceTime(1.0, 1.0, 28900),
                    distanceInM = 1000D
                  )
                ),
                beamVehicleId = dummyAgentVehicleId,
                asDriver = true,
                passengerSchedule = None,
                cost = ZERO,
                unbecomeDriverOnCompletion = true
              )
            )
          )
        ),
        staticRequestId = java.util.UUID.randomUUID()
      )

      // The agent will ask for a ride, and we will answer.
      val inquiry = expectMsgType[RideHailRequest]
      personActor ! RideHailResponse(inquiry, None, None)

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

//    ignore("should know how to take a car trip when it's already in its plan") {
//      val eventsManager = new EventsManagerImpl()
//      eventsManager.addHandler(
//        new BasicEventHandler {
//          override def handleEvent(event: Event): Unit = {
//            self ! event
//          }
//        }
//      )
//      val vehicleId = Id.createVehicleId(1)
//      val beamVehicle = new BeamVehicle(
//        vehicleId,
//        new Powertrain(0.0),
//        None,
//        BeamVehicleType.defaultCarBeamVehicleType,
//        None,
//        None
//      )
//      vehicles.put(vehicleId, beamVehicle)
//      val household = householdsFactory.createHousehold(hoseHoldDummyId)
//      val population = PopulationUtils.createPopulation(ConfigUtils.createConfig())
//
//      val person = PopulationUtils.getFactory.createPerson(Id.createPersonId("dummyAgent"))
//      population.getPersonAttributes.putAttribute(
//        person.getId.toString,
//        PlansSampler.availableModeString,
//        "car,ride_hail,bike,bus,funicular,gondola,cable_car,ferry,tram,transit,rail,subway,tram"
//      )
//      population.getPersonAttributes
//        .putAttribute(person.getId.toString, "valueOfTime", 15.0)
//      val plan = PopulationUtils.getFactory.createPlan()
//      val homeActivity = PopulationUtils.createActivityFromLinkId("home", Id.createLinkId(1))
//      homeActivity.setEndTime(28800) // 8:00:00 AM
//      homeActivity.setCoord(new Coord(0.0, 0.0))
//      plan.addActivity(homeActivity)
//      val leg = PopulationUtils.createLeg("car")
//      val route = RouteUtils.createLinkNetworkRouteImpl(
//        Id.createLinkId(1),
//        Array[Id[Link]](),
//        Id.createLinkId(2)
//      )
//      route.setVehicleId(vehicleId)
//      leg.setRoute(route)
//      plan.addLeg(leg)
//      val workActivity = PopulationUtils.createActivityFromLinkId("work", Id.createLinkId(2))
//      workActivity.setEndTime(61200) //5:00:00 PM
//      workActivity.setCoord(new Coord(1.0, 1.0))
//      plan.addActivity(workActivity)
//      person.addPlan(plan)
//      population.addPerson(person)
//      household.setMemberIds(JavaConverters.bufferAsJavaList(mutable.Buffer(person.getId)))
//      val scenario = ScenarioUtils.createMutableScenario(matsimConfig)
//      scenario.setPopulation(population)
//      scenario.setLocked()
//      ScenarioUtils.loadScenario(scenario)
//      val attributesOfIndividual = AttributesOfIndividual(
//        person,
//        household,
//        Map(Id.create(vehicleId, classOf[BeamVehicle]) -> beamVehicle),
//        Seq(CAR),
//        BigDecimal(18.0)
//      )
//      person.getCustomAttributes.put("beam-attributes", attributesOfIndividual)
//      when(beamSvc.matsimServices.getScenario).thenReturn(scenario)
//
//      val scheduler = TestActorRef[BeamAgentScheduler](
//        SchedulerProps(
//          beamConfig,
//          stopTick = 1000000,
//          maxWindow = 10,
//          new StuckFinder(beamConfig.beam.debug.stuckAgentDetection)
//        )
//      )
//
//      val householdActor = TestActorRef[HouseholdActor](
//        new HouseholdActor(
//          beamSvc,
//          _ => modeChoiceCalculator,
//          scheduler,
//          networkCoordinator.transportNetwork,
//          self,
//          self,
//          parkingManager,
//          eventsManager,
//          population,
//          household.getId,
//          household,
//          Map(beamVehicle.getId -> beamVehicle),
//          new Coord(0.0, 0.0)
//        )
//      )
//      val personActor = householdActor.getSingleChild(person.getId.toString)
//
//      scheduler ! StartSchedule(0)
//
//      // The agent will ask for current travel times for a route it already knows.
//      val embodyRequest = expectMsgType[EmbodyWithCurrentTravelTime]
//      personActor ! RoutingResponse(
//        Vector(
//          EmbodiedBeamTrip(
//            legs = Vector(
//              EmbodiedBeamLeg(
//                beamLeg = embodyRequest.leg.copy(duration = 500),
//                beamVehicleId = dummyAgentVehicleId,
//                asDriver = true,
//                passengerSchedule = None,
//                cost = ZERO,
//                unbecomeDriverOnCompletion = false
//              ),
//              EmbodiedBeamLeg(
//                beamLeg = embodyRequest.leg.copy(duration = 500),
//                beamVehicleId = dummyAgentVehicleId,
//                asDriver = true,
//                passengerSchedule = None,
//                cost = ZERO,
//                unbecomeDriverOnCompletion = true
//              )
//            )
//          )
//        ),
//        staticRequestId = java.util.UUID.randomUUID()
//      )
//
//      expectMsgType[ModeChoiceEvent]
//      expectMsgType[ActivityEndEvent]
//      expectMsgType[PersonDepartureEvent]
//
//      expectMsgType[PersonEntersVehicleEvent]
//      expectMsgType[VehicleEntersTrafficEvent]
//      expectMsgType[LinkLeaveEvent]
//      expectMsgType[LinkEnterEvent]
//      expectMsgType[VehicleLeavesTrafficEvent]
//
//      expectMsgType[PathTraversalEvent]
//      expectMsgType[PersonLeavesVehicleEvent]
//      expectMsgType[TeleportationArrivalEvent]
//
//      expectMsgType[PersonArrivalEvent]
//      expectMsgType[ActivityStartEvent]
//
//      expectMsgType[CompletionNotice]
//    }

    it("should know how to take a walk_transit trip when it's already in its plan") {

      // In this tests, it's not easy to chronologically sort Events vs. Triggers/Messages
      // that we are expecting. And also not necessary in real life.
      // So we put the Events on a separate channel to avoid a non-deterministically failing test.
      val events = new TestProbe(system)
      val eventsManager: EventsManager = new EventsManagerImpl()
      eventsManager.addHandler(
        new BasicEventHandler {
          override def handleEvent(event: Event): Unit = {
            events.ref ! event
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
        cost = ZERO,
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
        cost = ZERO,
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
        cost = ZERO,
        unbecomeDriverOnCompletion = false
      )

      val household = householdsFactory.createHousehold(hoseHoldDummyId)
      val population = PopulationUtils.createPopulation(ConfigUtils.createConfig())
      val person = PopulationUtils.getFactory.createPerson(Id.createPersonId("dummyAgent"))
      population.getPersonAttributes.putAttribute(
        person.getId.toString,
        PlansSampler.availableModeString,
        "car,ride_hail,bike,bus,funicular,gondola,cable_car,ferry,tram,transit,rail,subway,tram"
      )
      population.getPersonAttributes.putAttribute(person.getId.toString, "valueOfTime", 15.0)
      val plan = PopulationUtils.getFactory.createPlan()
      val homeActivity =
        PopulationUtils.createActivityFromCoord("home", new Coord(166321.9, 1568.87))
      homeActivity.setEndTime(28800) // 8:00:00 AM
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
      workActivity.setEndTime(61200) //5:00:00 PM
      plan.addActivity(workActivity)
      person.addPlan(plan)
      population.addPerson(person)
      household.setMemberIds(JavaConverters.bufferAsJavaList(mutable.Buffer(person.getId)))
      val scheduler = TestActorRef[BeamAgentScheduler](
        SchedulerProps(
          beamConfig,
          stopTick = 1000000,
          maxWindow = 10,
          new StuckFinder(beamConfig.beam.debug.stuckAgentDetection)
        )
      )

      val scenario = ScenarioUtils.createMutableScenario(matsimConfig)
      scenario.setPopulation(population)
      scenario.setLocked()
      ScenarioUtils.loadScenario(scenario)
      when(beamSvc.matsimServices.getScenario).thenReturn(scenario)

      bus.becomeDriver(
        Await.result(
          system.actorSelection("/user/router/TransitDriverAgent-my_bus").resolveOne(),
          timeout.duration
        )
      )
      tram.becomeDriver(
        Await.result(
          system.actorSelection("/user/router/TransitDriverAgent-my_tram").resolveOne(),
          timeout.duration
        )
      )

      val householdActor = TestActorRef[HouseholdActor](
        new HouseholdActor(
          beamServices = beamSvc,
          modeChoiceCalculatorFactory = _ => modeChoiceCalculator,
          schedulerRef = scheduler,
          transportNetwork = networkCoordinator.transportNetwork,
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

      val request6 = expectMsgType[RoutingRequest]
      lastSender ! RoutingResponse(
        itineraries = Vector(
          EmbodiedBeamTrip(
            legs = Vector(
              EmbodiedBeamLeg(
                beamLeg = BeamLeg(
                  startTime = 28800,
                  mode = BeamMode.WALK,
                  duration = 0,
                  travelPath = BeamPath(
                    linkIds = Vector(),
                    linkTravelTime = Vector(),
                    transitStops = None,
                    startPoint = SpaceTime(new Coord(166321.9, 1568.87), 28800),
                    endPoint = SpaceTime(new Coord(167138.4, 1117), 28800),
                    distanceInM = 1D
                  )
                ),
                beamVehicleId = dummyAgentVehicleId,
                asDriver = true,
                passengerSchedule = None,
                cost = ZERO,
                unbecomeDriverOnCompletion = false
              ),
              busLeg,
              busLeg2,
              tramLeg,
              EmbodiedBeamLeg(
                beamLeg = BeamLeg(
                  startTime = 30600,
                  mode = BeamMode.WALK,
                  duration = 0,
                  travelPath = BeamPath(
                    linkIds = Vector(),
                    linkTravelTime = Vector(),
                    transitStops = None,
                    startPoint = SpaceTime(new Coord(167138.4, 1117), 30600),
                    endPoint = SpaceTime(new Coord(167138.4, 1117), 30600),
                    distanceInM = 1D
                  )
                ),
                beamVehicleId = dummyAgentVehicleId,
                asDriver = true,
                passengerSchedule = None,
                cost = ZERO,
                unbecomeDriverOnCompletion = false
              )
            )
          )
        ),
        staticRequestId = java.util.UUID.randomUUID()
      )

      events.expectMsgType[ModeChoiceEvent]
      events.expectMsgType[ActivityEndEvent]
      events.expectMsgType[PersonDepartureEvent]

      events.expectMsgType[PersonEntersVehicleEvent]
      events.expectMsgType[VehicleEntersTrafficEvent]
      events.expectMsgType[VehicleLeavesTrafficEvent]
      events.expectMsgType[PathTraversalEvent]

      val reservationRequestBus = expectMsgType[ReservationRequest]
      scheduler ! ScheduleTrigger(
        NotifyLegStartTrigger(28800, busLeg.beamLeg, busLeg.beamVehicleId),
        personActor
      )
      scheduler ! ScheduleTrigger(
        NotifyLegEndTrigger(29400, busLeg.beamLeg, busLeg.beamVehicleId),
        personActor
      )
      scheduler ! ScheduleTrigger(
        NotifyLegStartTrigger(29400, busLeg2.beamLeg, busLeg.beamVehicleId),
        personActor
      )
      scheduler ! ScheduleTrigger(
        NotifyLegEndTrigger(30000, busLeg2.beamLeg, busLeg.beamVehicleId),
        personActor
      )
      lastSender ! ReservationResponse(
        reservationRequestBus.requestId,
        Right(
          ReserveConfirmInfo(
            busLeg.beamLeg,
            busLeg2.beamLeg,
            reservationRequestBus.passengerVehiclePersonId
          )
        ),
        TRANSIT
      )

      events.expectMsgType[PersonEntersVehicleEvent]
      events.expectMsgType[PersonLeavesVehicleEvent]

      val reservationRequestTram = expectMsgType[ReservationRequest]
      lastSender ! ReservationResponse(
        reservationRequestTram.requestId,
        Right(
          ReserveConfirmInfo(
            tramLeg.beamLeg,
            tramLeg.beamLeg,
            reservationRequestBus.passengerVehiclePersonId
          )
        ),
        TRANSIT
      )
      scheduler ! ScheduleTrigger(
        NotifyLegStartTrigger(30000, tramLeg.beamLeg, tramLeg.beamVehicleId),
        personActor
      )
      scheduler ! ScheduleTrigger(
        NotifyLegEndTrigger(32000, tramLeg.beamLeg, tramLeg.beamVehicleId),
        personActor
      ) // My tram is late!
      events.expectMsgType[PersonEntersVehicleEvent]
      events.expectMsgType[PersonLeavesVehicleEvent]

      events.expectMsgType[VehicleEntersTrafficEvent]
      events.expectMsgType[VehicleLeavesTrafficEvent]
      events.expectMsgType[PathTraversalEvent]

      events.expectMsgType[TeleportationArrivalEvent]
      events.expectMsgType[PersonArrivalEvent]
      events.expectMsgType[ActivityStartEvent]

      expectMsgType[CompletionNotice]
    }

  }

  override def beforeAll: Unit = {
    networkCoordinator.loadNetwork()
  }

  override def afterAll: Unit = {
    shutdown()
  }

}

object PersonAgentSpec {
  val ZERO: BigDecimal = BigDecimal(0)
}
