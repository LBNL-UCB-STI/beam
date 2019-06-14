package beam.agentsim.agents

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.testkit.TestActors.ForwardActor
import akka.testkit.{ImplicitSender, TestActorRef, TestFSMRef, TestKit, TestProbe}
import beam.agentsim.agents.PersonTestUtil._
import beam.agentsim.agents.household.HouseholdActor.HouseholdActor
import beam.agentsim.agents.modalbehaviors.DrivesVehicle.{AlightVehicleTrigger, BoardVehicleTrigger}
import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator
import beam.agentsim.agents.ridehail.{RideHailRequest, RideHailResponse}
import beam.agentsim.agents.vehicles.{ReservationRequest, ReservationResponse, ReserveConfirmInfo, _}
import beam.agentsim.events._
import beam.agentsim.infrastructure.TrivialParkingManager
import beam.agentsim.scheduler.BeamAgentScheduler
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger, SchedulerProps, StartSchedule}
import beam.router.BeamRouter._
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{RIDE_HAIL, RIDE_HAIL_TRANSIT, TRANSIT, WALK, WALK_TRANSIT}
import beam.router.model.RoutingModel.TransitStopsInfo
import beam.router.model.{EmbodiedBeamLeg, _}
import beam.router.{BeamSkimmer, RouteHistory, TravelTimeObserved}
import beam.sim.BeamServices
import beam.sim.population.AttributesOfIndividual
import beam.utils.TestConfigUtils.testConfig
import beam.utils.{SimRunnerForTest, StuckFinder, TestConfigUtils}
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.events._
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.population.{Activity, Person}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.api.experimental.events.{EventsManager, TeleportationArrivalEvent}
import org.matsim.core.config.ConfigUtils
import org.matsim.core.events.EventsManagerImpl
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.population.PopulationUtils
import org.matsim.core.population.routes.RouteUtils
import org.matsim.households.{Household, HouseholdsFactoryImpl}
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FunSpecLike}

import scala.collection.mutable.ListBuffer
import scala.collection.{JavaConverters, mutable}

class PersonAgentSpec
    extends {
      val config = testConfig("test/input/beamville/beam.conf").resolve()
      private val personAgentSpec = ActorSystem(
        name = "PersonAgentSpec",
        config = ConfigFactory
          .parseString(
            """
        akka.log-dead-letters = 10
        akka.actor.debug.fsm = true
        akka.loglevel = debug
        """
          )
          .withFallback(config)
      )
    } with TestKit(
      personAgentSpec
    )
      with FunSpecLike
      with SimRunnerForTest
    with BeforeAndAfterAll
    with MockitoSugar
    with ImplicitSender {

  override def outputDirPath: String = TestConfigUtils.testOutputDir

  private val householdsFactory: HouseholdsFactoryImpl = new HouseholdsFactoryImpl()

  private lazy val modeChoiceCalculator = new ModeChoiceCalculator {
    override def apply(
      alternatives: IndexedSeq[EmbodiedBeamTrip],
      attributesOfIndividual: AttributesOfIndividual,
      destinationActivity: Option[Activity]
    ): Option[EmbodiedBeamTrip] =
      Some(alternatives.head)

    override val beamServices: BeamServices = services

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

  // Mock a transit driver (who has to be a child of a mock router)
  private lazy val transitDriverProps = Props(new ForwardActor(self))

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
      val parkingManager = system.actorOf(Props(new TrivialParkingManager))
      val household = householdsFactory.createHousehold(hoseHoldDummyId)
      val person = PopulationUtils.getFactory.createPerson(Id.createPersonId("dummyAgent"))
      putDefaultBeamAttributes(person, Vector(WALK))
      val homeActivity = PopulationUtils.createActivityFromLinkId("home", Id.createLinkId(1))
      homeActivity.setStartTime(1.0)
      homeActivity.setEndTime(10.0)
      val plan = PopulationUtils.getFactory.createPlan()
      plan.addActivity(homeActivity)
      person.addPlan(plan)
      val personAgentRef = TestFSMRef(
        new PersonAgent(
          scheduler,
          services,
          beamScenario,
          modeChoiceCalculator,
          beamScenario.transportNetwork,
          self,
          self,
          eventsManager,
          Id.create("dummyAgent", classOf[PersonAgent]),
          plan,
          parkingManager,
          tollCalculator,
          self,
          beamSkimmer = new BeamSkimmer(beamScenario, services.geo),
          routeHistory = new RouteHistory(beamConfig),
          travelTimeObserved = new TravelTimeObserved(beamScenario, services.geo)
        )
      )

      watch(personAgentRef)
      scheduler ! ScheduleTrigger(InitializeTrigger(0), personAgentRef)
      scheduler ! StartSchedule(0)
      expectTerminated(personAgentRef)
      expectMsg(CompletionNotice(0, Vector()))
    }

    // Hopefully deterministic test, where we mock a router and give the agent just one option for its trip.
    it("should demonstrate a complete trip, throwing MATSim events") {
      val eventsManager = new EventsManagerImpl()
      eventsManager.addHandler(
        new BasicEventHandler {
          override def handleEvent(event: Event): Unit = {
            self ! event
          }
        }
      )

      val household = householdsFactory.createHousehold(hoseHoldDummyId)
      val population = PopulationUtils.createPopulation(matsimConfig)
      val person = PopulationUtils.getFactory.createPerson(Id.createPersonId("dummyAgent"))
      putDefaultBeamAttributes(person, Vector(RIDE_HAIL, RIDE_HAIL_TRANSIT, WALK))
      val plan = PopulationUtils.getFactory.createPlan()
      val homeActivity = PopulationUtils.createActivityFromLinkId("home", Id.createLinkId(1))
      homeActivity.setEndTime(28800) // 8:00:00 AM
      plan.addActivity(homeActivity)
      val workActivity = PopulationUtils.createActivityFromLinkId("work", Id.createLinkId(2))
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
      val parkingManager = system.actorOf(Props(new TrivialParkingManager))

      val householdActor = TestActorRef[HouseholdActor](
        new HouseholdActor(
          services,
          beamScenario,
          _ => modeChoiceCalculator,
          scheduler,
          beamScenario.transportNetwork,
          tollCalculator,
          self,
          self,
          parkingManager,
          eventsManager,
          population,
          household,
          Map(),
          new Coord(0.0, 0.0),
          Vector(),
          new RouteHistory(beamConfig),
          new BeamSkimmer(beamScenario, services.geo),
          new TravelTimeObserved(beamScenario, services.geo)
        )
      )
      scheduler ! ScheduleTrigger(InitializeTrigger(0), householdActor)

      scheduler ! StartSchedule(0)

      // The agent will ask for a ride, and we will answer.
      val inquiry = expectMsgType[RideHailRequest]
      lastSender ! RideHailResponse(inquiry, None, None)

      // This is the ridehail to transit request.
      // We don't provide an option.
      val request1 = expectMsgType[RoutingRequest]
      assert(request1.streetVehiclesUseIntermodalUse == AccessAndEgress)
      lastSender ! RoutingResponse(
        itineraries = Vector(),
        requestId = request1.requestId
      )

      // This is the regular routing request.
      // We provide an option.
      val request2 = expectMsgType[RoutingRequest]
      assert(request2.streetVehiclesUseIntermodalUse == Access)
      lastSender ! RoutingResponse(
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
                    linkTravelTime = Vector(50, 50),
                    transitStops = None,
                    startPoint = SpaceTime(0.0, 0.0, 28800),
                    endPoint = SpaceTime(1.0, 1.0, 28900),
                    distanceInM = 1000D
                  )
                ),
                beamVehicleId = Id.createVehicleId("body-dummyAgent"),
                Id.create("BODY-TYPE-DEFAULT", classOf[BeamVehicleType]),
                asDriver = true,
                cost = 0.0,
                unbecomeDriverOnCompletion = true
              )
            )
          )
        ),
        requestId = request2.requestId
      )

      expectMsgType[ModeChoiceEvent]
      expectMsgType[ActivityEndEvent]
      expectMsgType[PersonDepartureEvent]

      expectMsgType[PersonEntersVehicleEvent]
      expectMsgType[VehicleEntersTrafficEvent]
      expectMsgType[VehicleLeavesTrafficEvent]

      expectMsgType[PathTraversalEvent]
      expectMsgType[PersonLeavesVehicleEvent]
      expectMsgType[TeleportationArrivalEvent]

      expectMsgType[PersonArrivalEvent]
      expectMsgType[ActivityStartEvent]

      expectMsgType[CompletionNotice]
    }

    it("should know how to take a walk_transit trip when it's already in its plan") {
      val busId = Id.createVehicleId("bus:B3-WEST-1-175")
      val tramId = Id.createVehicleId("train:R2-SOUTH-1-93")

      val iteration: ActorRef = system.actorOf(
        Props(new Actor() {
          context.actorOf(
            Props(new Actor() {
              context.actorOf(transitDriverProps, "TransitDriverAgent-" + busId.toString)
              context.actorOf(transitDriverProps, "TransitDriverAgent-" + tramId.toString)

              override def receive: Receive = Actor.emptyBehavior
            }),
            "transit-system"
          )

          override def receive: Receive = Actor.emptyBehavior
        }),
        "BeamMobsim.iteration"
      )

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

      val busLeg = EmbodiedBeamLeg(
        BeamLeg(
          startTime = 28800,
          mode = BeamMode.BUS,
          duration = 600,
          travelPath = BeamPath(
            Vector(),
            Vector(),
            Some(TransitStopsInfo(1, busId, 2)),
            SpaceTime(services.geo.utm2Wgs(new Coord(166321.9, 1568.87)), 28800),
            SpaceTime(services.geo.utm2Wgs(new Coord(167138.4, 1117)), 29400),
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
            SpaceTime(services.geo.utm2Wgs(new Coord(167138.4, 1117)), 29400),
            SpaceTime(services.geo.utm2Wgs(new Coord(180000.4, 1200)), 30000),
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
            startPoint = SpaceTime(services.geo.utm2Wgs(new Coord(180000.4, 1200)), 30000),
            endPoint = SpaceTime(services.geo.utm2Wgs(new Coord(190000.4, 1300)), 30600),
            distanceInM = 1.0
          )
        ),
        beamVehicleId = tramId,
        Id.create("TRANSIT-TYPE-DEFAULT", classOf[BeamVehicleType]),
        asDriver = false,
        cost = 1.0, // $1 fare
        unbecomeDriverOnCompletion = false
      )

      val household = householdsFactory.createHousehold(hoseHoldDummyId)
      val population = PopulationUtils.createPopulation(ConfigUtils.createConfig())
      val person = PopulationUtils.getFactory.createPerson(Id.createPersonId("dummyAgent"))
      putDefaultBeamAttributes(person, Vector(WALK_TRANSIT))
      val plan = PopulationUtils.getFactory.createPlan()
      val homeActivity = PopulationUtils.createActivityFromCoord("home", new Coord(166321.9, 1568.87))
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
      val parkingManager = system.actorOf(Props(new TrivialParkingManager))
      val householdActor = TestActorRef[HouseholdActor](
        new HouseholdActor(
          beamServices = services,
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
          new BeamSkimmer(beamScenario, services.geo),
          new TravelTimeObserved(beamScenario, services.geo)
        )
      )
      scheduler ! ScheduleTrigger(InitializeTrigger(0), householdActor)
      scheduler ! StartSchedule(0)

      expectMsgType[RoutingRequest]
      val personActor = lastSender
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
                    startPoint = SpaceTime(services.geo.utm2Wgs(new Coord(166321.9, 1568.87)), 28800),
                    endPoint = SpaceTime(services.geo.utm2Wgs(new Coord(167138.4, 1117)), 28800),
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
                  duration = 0,
                  travelPath = BeamPath(
                    linkIds = Vector(),
                    linkTravelTime = Vector(),
                    transitStops = None,
                    startPoint = SpaceTime(services.geo.utm2Wgs(new Coord(167138.4, 1117)), 30600),
                    endPoint = SpaceTime(services.geo.utm2Wgs(new Coord(167138.4, 1117)), 30600),
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

      events.expectMsgType[ModeChoiceEvent]
      events.expectMsgType[ActivityEndEvent]
      events.expectMsgType[PersonDepartureEvent]

      events.expectMsgType[PersonEntersVehicleEvent]
      events.expectMsgType[VehicleEntersTrafficEvent]
      events.expectMsgType[VehicleLeavesTrafficEvent]
      events.expectMsgType[PathTraversalEvent]

      val reservationRequestBus = expectMsgType[ReservationRequest]
      scheduler ! ScheduleTrigger(
        BoardVehicleTrigger(28800, busLeg.beamVehicleId),
        personActor
      )
      scheduler ! ScheduleTrigger(
        AlightVehicleTrigger(30000, busLeg.beamVehicleId),
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

      events.expectMsgType[AgencyRevenueEvent]
      events.expectMsgType[PersonCostEvent]

      //Generating 1 event of PersonCost having 0.0 cost in between PersonEntersVehicleEvent & PersonLeavesVehicleEvent

      events.expectMsgType[PersonLeavesVehicleEvent]

      val reservationRequestTram = expectMsgType[ReservationRequest]
      lastSender ! ReservationResponse(
        reservationRequestTram.requestId,
        Right(
          ReserveConfirmInfo(
            tramLeg.beamLeg,
            tramLeg.beamLeg,
            reservationRequestTram.passengerVehiclePersonId,
            Vector(
              ScheduleTrigger(
                BoardVehicleTrigger(
                  30000,
                  tramLeg.beamVehicleId
                ),
                personActor
              ),
              ScheduleTrigger(
                AlightVehicleTrigger(
                  32000,
                  tramLeg.beamVehicleId
                ),
                personActor
              ) // My tram is late!
            )
          )
        ),
        TRANSIT
      )

      //expects a message of type PersonEntersVehicleEvent
      events.expectMsgType[PersonEntersVehicleEvent]

      events.expectMsgType[AgencyRevenueEvent]
      events.expectMsgType[PersonCostEvent]
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

  override def afterAll(): Unit = {
    shutdown()
  }

}
