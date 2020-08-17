package beam.agentsim.agents

import akka.actor.{Actor, ActorRef, ActorSystem, PoisonPill, Props}
import akka.testkit.TestActors.ForwardActor
import akka.testkit.{ImplicitSender, TestActorRef, TestFSMRef, TestKitBase, TestProbe}
import beam.agentsim.agents.PersonTestUtil._
import beam.agentsim.agents.choice.mode.ModeChoiceUniformRandom
import beam.agentsim.agents.household.HouseholdActor.HouseholdActor
import beam.agentsim.agents.modalbehaviors.DrivesVehicle.{AlightVehicleTrigger, BoardVehicleTrigger}
import beam.agentsim.agents.ridehail.{RideHailRequest, RideHailResponse}
import beam.agentsim.agents.vehicles.{ReservationResponse, ReserveConfirmInfo, _}
import beam.agentsim.events._
import beam.agentsim.infrastructure.{TrivialParkingManager, ZonalParkingManager}
import beam.agentsim.scheduler.BeamAgentScheduler
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger, SchedulerProps, StartSchedule}
import beam.router.BeamRouter._
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{RIDE_HAIL, RIDE_HAIL_TRANSIT, WALK, WALK_TRANSIT}
import beam.router.RouteHistory
import beam.router.model.RoutingModel.TransitStopsInfo
import beam.router.model.{EmbodiedBeamLeg, _}
import beam.router.osm.TollCalculator
import beam.router.skim.AbstractSkimmerEvent
import beam.utils.TestConfigUtils.testConfig
import beam.utils.{SimRunnerForTest, StuckFinder, TestConfigUtils}
import com.typesafe.config.{Config, ConfigFactory}
import org.matsim.api.core.v01.events._
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.api.experimental.events.{EventsManager, TeleportationArrivalEvent}
import org.matsim.core.config.ConfigUtils
import org.matsim.core.events.EventsManagerImpl
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.population.PopulationUtils
import org.matsim.core.population.routes.RouteUtils
import org.matsim.households.{Household, HouseholdsFactoryImpl}
import org.scalatest.{BeforeAndAfter, FunSpecLike}
import org.scalatestplus.mockito.MockitoSugar

import scala.collection.{mutable, JavaConverters}

class PersonAgentSpec
    extends FunSpecLike
    with TestKitBase
    with SimRunnerForTest
    with BeforeAndAfter
    with MockitoSugar
    with ImplicitSender
    with BeamvilleFixtures {

  lazy val config: Config = ConfigFactory
    .parseString(
      """
        akka.log-dead-letters = 10
        akka.actor.debug.fsm = true
        akka.loglevel = debug
        """
    )
    .withFallback(testConfig("test/input/beamville/beam.conf"))
    .resolve()

  lazy implicit val system: ActorSystem = ActorSystem("PersonAgentSpec", config)

  override def outputDirPath: String = TestConfigUtils.testOutputDir

  private val householdsFactory: HouseholdsFactoryImpl = new HouseholdsFactoryImpl()

  private lazy val modeChoiceCalculator = new ModeChoiceUniformRandom(beamConfig)

  // Mock a transit driver (who has to be a child of a mock router)
  private lazy val transitDriverProps = Props(new ForwardActor(self))

  private var maybeIteration: Option[ActorRef] = None
  private val terminationProbe = TestProbe()

  describe("A PersonAgent") {

    val hoseHoldDummyId = Id.create("dummy", classOf[Household])

    it("should allow scheduler to set the first activity") {
      val eventsManager = new EventsManagerImpl()
      eventsManager.addHandler(
        new BasicEventHandler {
          override def handleEvent(event: Event): Unit = {
            event match {
              case _: AbstractSkimmerEvent => // ignore
              case _                       => self ! event
            }
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
          services.tollCalculator,
          self,
          routeHistory = new RouteHistory(beamConfig),
          boundingBox = boundingBox
        )
      )

      watch(personAgentRef)
      scheduler ! ScheduleTrigger(InitializeTrigger(0), personAgentRef)
      scheduler ! StartSchedule(0)
      expectMsg(CompletionNotice(0, Vector()))
    }

    // Hopefully deterministic test, where we mock a router and give the agent just one option for its trip.
    it("should demonstrate a complete trip, throwing MATSim events") {
      val eventsManager = new EventsManagerImpl()
      eventsManager.addHandler(
        new BasicEventHandler {
          override def handleEvent(event: Event): Unit = {
            event match {
              case _: AbstractSkimmerEvent => // ignore
              case _                       => self ! event
            }
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
          services.tollCalculator,
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
          boundingBox
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
        requestId = request1.requestId,
        request = None,
        isEmbodyWithCurrentTravelTime = false
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
                    endPoint = SpaceTime(1.0, 1.0, 28850),
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
        requestId = request2.requestId,
        request = None,
        isEmbodyWithCurrentTravelTime = false
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

      maybeIteration = Some(
        TestActorRef(
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
      )
      terminationProbe.watch(maybeIteration.get)

      // In this tests, it's not easy to chronologically sort Events vs. Triggers/Messages
      // that we are expecting. And also not necessary in real life.
      // So we put the Events on a separate channel to avoid a non-deterministically failing test.
      val events = new TestProbe(system)
      val eventsManager: EventsManager = new EventsManagerImpl()
      eventsManager.addHandler(
        new BasicEventHandler {
          override def handleEvent(event: Event): Unit = {
            event match {
              case _: AbstractSkimmerEvent => // ignore
              case _                       => events.ref ! event
            }
          }
        }
      )

      val busPassengerLeg = EmbodiedBeamLeg(
        beamLeg = BeamLeg(
          startTime = 28800,
          mode = BeamMode.BUS,
          duration = 1200,
          travelPath = BeamPath(
            Vector(),
            Vector(),
            Some(TransitStopsInfo("someAgency", "someRoute", busId, 0, 2)),
            SpaceTime(services.geo.utm2Wgs(new Coord(166321.9, 1568.87)), 28800),
            SpaceTime(services.geo.utm2Wgs(new Coord(180000.4, 1200)), 30000),
            2.0
          )
        ),
        beamVehicleId = busId,
        Id.create("TRANSIT-TYPE-DEFAULT", classOf[BeamVehicleType]),
        asDriver = false,
        cost = 2.75,
        unbecomeDriverOnCompletion = false
      )

      val tramPassengerLeg = EmbodiedBeamLeg(
        beamLeg = BeamLeg(
          startTime = 30000,
          mode = BeamMode.TRAM,
          duration = 600,
          travelPath = BeamPath(
            linkIds = Vector(),
            linkTravelTime = Vector(),
            transitStops = Some(TransitStopsInfo("someAgency", "someRoute", tramId, 0, 1)),
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
          services.tollCalculator,
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
          boundingBox
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
              busPassengerLeg,
              tramPassengerLeg,
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
        requestId = 1,
        request = None,
        isEmbodyWithCurrentTravelTime = false
      )

      events.expectMsgType[ModeChoiceEvent]
      events.expectMsgType[ActivityEndEvent]
      events.expectMsgType[PersonDepartureEvent]

      events.expectMsgType[PersonEntersVehicleEvent]
      events.expectMsgType[VehicleEntersTrafficEvent]
      events.expectMsgType[VehicleLeavesTrafficEvent]
      events.expectMsgType[PathTraversalEvent]

      expectMsgType[TransitReservationRequest]
      scheduler ! ScheduleTrigger(
        BoardVehicleTrigger(28800, busPassengerLeg.beamVehicleId),
        personActor
      )
      scheduler ! ScheduleTrigger(
        AlightVehicleTrigger(30000, busPassengerLeg.beamVehicleId),
        personActor
      )
      lastSender ! ReservationResponse(Right(ReserveConfirmInfo()))

      events.expectMsgType[PersonEntersVehicleEvent]

      events.expectMsgType[AgencyRevenueEvent]
      events.expectMsgType[PersonCostEvent]

      //Generating 1 event of PersonCost having 0.0 cost in between PersonEntersVehicleEvent & PersonLeavesVehicleEvent

      events.expectMsgType[PersonLeavesVehicleEvent]

      expectMsgType[TransitReservationRequest]
      lastSender ! ReservationResponse(
        Right(
          ReserveConfirmInfo(
            Vector(
              ScheduleTrigger(
                BoardVehicleTrigger(
                  30000,
                  tramPassengerLeg.beamVehicleId
                ),
                personActor
              ),
              ScheduleTrigger(
                AlightVehicleTrigger(
                  32000,
                  tramPassengerLeg.beamVehicleId
                ),
                personActor
              ) // My tram is late!
            )
          )
        )
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

    it("should also work when the first bus is late") {
      val eventsManager = new EventsManagerImpl()
      val events = new TestProbe(system)
      eventsManager.addHandler(new BasicEventHandler {
        override def handleEvent(event: Event): Unit = {
          event match {
            case _: AbstractSkimmerEvent => // ignore
            case _                       => events.ref ! event
          }
        }
      })
      val transitDriverProps = Props(new ForwardActor(self))
      val busId = Id.createVehicleId("bus:B3-WEST-1-175")
      val tramId = Id.createVehicleId("train:R2-SOUTH-1-93")

      maybeIteration = Some(
        TestActorRef(
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
      )
      terminationProbe.watch(maybeIteration.get)

      val busPassengerLeg = EmbodiedBeamLeg(
        BeamLeg(
          28800,
          BeamMode.BUS,
          1200,
          BeamPath(
            Vector(),
            Vector(),
            Some(TransitStopsInfo("someAgency", "someRoute", busId, 0, 2)),
            SpaceTime(services.geo.utm2Wgs(new Coord(166321.9, 1568.87)), 28800),
            SpaceTime(services.geo.utm2Wgs(new Coord(180000.4, 1200)), 30000),
            2.0
          )
        ),
        busId,
        Id.create("beamVilleCar", classOf[BeamVehicleType]),
        asDriver = false,
        0,
        unbecomeDriverOnCompletion = false
      )
      val tramPassengerLeg = EmbodiedBeamLeg(
        BeamLeg(
          30000,
          BeamMode.TRAM,
          600,
          BeamPath(
            Vector(),
            Vector(),
            Some(TransitStopsInfo("someAgency", "someRoute", tramId, 0, 1)),
            SpaceTime(services.geo.utm2Wgs(new Coord(180000.4, 1200)), 30000),
            SpaceTime(services.geo.utm2Wgs(new Coord(190000.4, 1300)), 30600),
            1.0
          )
        ),
        tramId,
        Id.create("beamVilleCar", classOf[BeamVehicleType]),
        asDriver = false,
        0,
        unbecomeDriverOnCompletion = false
      )
      val replannedTramLeg = EmbodiedBeamLeg(
        BeamLeg(
          35000,
          BeamMode.TRAM,
          600,
          BeamPath(
            Vector(),
            Vector(),
            Some(TransitStopsInfo("someAgency", "someRoute", tramId, 0, 1)),
            SpaceTime(services.geo.utm2Wgs(new Coord(180000.4, 1200)), 35000),
            SpaceTime(services.geo.utm2Wgs(new Coord(190000.4, 1300)), 35600),
            1.0
          )
        ),
        tramId,
        Id.create("beamVilleCar", classOf[BeamVehicleType]),
        asDriver = false,
        0,
        unbecomeDriverOnCompletion = false
      )

      val household = householdsFactory.createHousehold(Id.create("dummy", classOf[Household]))
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

      val parkingManager = system.actorOf(
        ZonalParkingManager.props(beamConfig, beamScenario.tazTreeMap, None, services.geo, services.beamRouter, boundingBox),
        "ParkingManager"
      )

      val householdActor = TestActorRef[HouseholdActor](
        new HouseholdActor(
          services,
          beamScenario,
          _ => modeChoiceCalculator,
          scheduler,
          beamScenario.transportNetwork,
          new TollCalculator(services.beamConfig),
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
          boundingBox
        )
      )
      scheduler ! ScheduleTrigger(InitializeTrigger(0), householdActor)
      scheduler ! StartSchedule(0)

      expectMsgType[RoutingRequest]
      val personActor = lastSender

      scheduler ! ScheduleTrigger(
        BoardVehicleTrigger(28800, busPassengerLeg.beamVehicleId),
        personActor
      )
      scheduler ! ScheduleTrigger(
        AlightVehicleTrigger(34400, busPassengerLeg.beamVehicleId),
        personActor
      )

      lastSender ! RoutingResponse(
        Vector(
          EmbodiedBeamTrip(
            Vector(
              EmbodiedBeamLeg(
                BeamLeg(
                  28800,
                  BeamMode.WALK,
                  0,
                  BeamPath(
                    Vector(),
                    Vector(),
                    None,
                    SpaceTime(services.geo.utm2Wgs(new Coord(166321.9, 1568.87)), 28800),
                    SpaceTime(services.geo.utm2Wgs(new Coord(167138.4, 1117)), 28800),
                    1.0
                  )
                ),
                Id.createVehicleId("body-dummyAgent"),
                Id.create("BODY-TYPE-DEFAULT", classOf[BeamVehicleType]),
                asDriver = true,
                0,
                unbecomeDriverOnCompletion = false
              ),
              busPassengerLeg,
              tramPassengerLeg,
              EmbodiedBeamLeg(
                BeamLeg(
                  30600,
                  BeamMode.WALK,
                  0,
                  BeamPath(
                    Vector(),
                    Vector(),
                    None,
                    SpaceTime(services.geo.utm2Wgs(new Coord(167138.4, 1117)), 30600),
                    SpaceTime(services.geo.utm2Wgs(new Coord(167138.4, 1117)), 30600),
                    1.0
                  )
                ),
                Id.createVehicleId("body-dummyAgent"),
                Id.create("BODY-TYPE-DEFAULT", classOf[BeamVehicleType]),
                asDriver = true,
                0,
                unbecomeDriverOnCompletion = false
              )
            )
          )
        ),
        requestId = 1,
        request = None,
        isEmbodyWithCurrentTravelTime = false
      )

      events.expectMsgType[ModeChoiceEvent]
      events.expectMsgType[ActivityEndEvent]
      events.expectMsgType[PersonDepartureEvent]

      events.expectMsgType[PersonEntersVehicleEvent]
      events.expectMsgType[VehicleEntersTrafficEvent]
      events.expectMsgType[VehicleLeavesTrafficEvent]
      events.expectMsgType[PathTraversalEvent]

      expectMsgType[TransitReservationRequest]

      lastSender ! ReservationResponse(
        Right(
          ReserveConfirmInfo()
        )
      )
      events.expectMsgType[PersonEntersVehicleEvent]

      //Generating 2 events of PersonCost having 0.0 cost in between PersonEntersVehicleEvent & PersonLeavesVehicleEvent

      val personLeavesVehicleEvent = events.expectMsgType[PersonLeavesVehicleEvent]
      assert(personLeavesVehicleEvent.getTime == 34400.0)

      events.expectMsgType[ReplanningEvent]
      expectMsgType[RoutingRequest]
      lastSender ! RoutingResponse(
        itineraries = Vector(
          EmbodiedBeamTrip(
            Vector(
              replannedTramLeg,
              EmbodiedBeamLeg(
                BeamLeg(
                  35600,
                  BeamMode.WALK,
                  0,
                  BeamPath(
                    Vector(),
                    Vector(),
                    None,
                    SpaceTime(services.geo.utm2Wgs(new Coord(167138.4, 1117)), 35600),
                    SpaceTime(services.geo.utm2Wgs(new Coord(167138.4, 1117)), 35600),
                    1.0
                  )
                ),
                Id.createVehicleId("body-dummyAgent"),
                Id.create("BODY-TYPE-DEFAULT", classOf[BeamVehicleType]),
                asDriver = true,
                0,
                unbecomeDriverOnCompletion = false
              )
            )
          )
        ),
        requestId = 1,
        request = None,
        isEmbodyWithCurrentTravelTime = false
      )
      events.expectMsgType[ModeChoiceEvent]

      // Person first does the dummy walk leg
      events.expectMsgType[VehicleEntersTrafficEvent]
      events.expectMsgType[VehicleLeavesTrafficEvent]
      events.expectMsgType[PathTraversalEvent]

      expectMsgType[TransitReservationRequest]
      lastSender ! ReservationResponse(
        Right(
          ReserveConfirmInfo(
            Vector(
              ScheduleTrigger(
                BoardVehicleTrigger(
                  35000,
                  replannedTramLeg.beamVehicleId
                ),
                personActor
              ),
              ScheduleTrigger(
                AlightVehicleTrigger(
                  40000,
                  replannedTramLeg.beamVehicleId
                ),
                personActor
              ) // My tram is late!
            )
          )
        )
      )

      events.expectMsgType[PersonEntersVehicleEvent]

      //Generating 2 events of PersonCost having 0.0 cost in between PersonEntersVehicleEvent & PersonLeavesVehicleEvent

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
    super.afterAll()
  }

  after {
    import scala.concurrent.duration._
    import scala.language.postfixOps
    maybeIteration.foreach { iteration =>
      iteration ! PoisonPill
      terminationProbe.expectTerminated(iteration, 60 seconds)
    }
    maybeIteration = None
    //we need to prevent getting this CompletionNotice from the Scheduler in the next test
    receiveWhile(1000 millis) {
      case _: CompletionNotice =>
    }
  }

}
