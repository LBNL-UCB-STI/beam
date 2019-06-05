package beam.agentsim.agents

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.testkit.TestActors.ForwardActor
import akka.testkit.{ImplicitSender, TestActorRef, TestKit}
import akka.util.Timeout
import beam.agentsim.agents.PersonTestUtil._
import beam.agentsim.agents.household.HouseholdActor.HouseholdActor
import beam.agentsim.agents.modalbehaviors.DrivesVehicle.{AlightVehicleTrigger, BoardVehicleTrigger}
import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator
import beam.agentsim.agents.vehicles.{ReservationRequest, ReservationResponse, ReserveConfirmInfo, _}
import beam.agentsim.events.{ModeChoiceEvent, PathTraversalEvent, ReplanningEvent, SpaceTime}
import beam.agentsim.infrastructure.ZonalParkingManager
import beam.agentsim.scheduler.BeamAgentScheduler
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger, SchedulerProps, StartSchedule}
import beam.router.BeamRouter.{RoutingRequest, RoutingResponse}
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{TRANSIT, WALK_TRANSIT}
import beam.router.model.RoutingModel.TransitStopsInfo
import beam.router.model.{EmbodiedBeamLeg, _}
import beam.router.osm.TollCalculator
import beam.router.r5.DefaultNetworkCoordinator
import beam.router.{BeamSkimmer, RouteHistory, TravelTimeObserved}
import beam.sim.population.AttributesOfIndividual
import beam.sim.{BeamHelper, BeamServices}
import beam.utils.StuckFinder
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.events._
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.population.{Activity, Person}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.api.experimental.events.TeleportationArrivalEvent
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

/**
  * Created by sfeygin on 2/7/17.
  */
class OtherPersonAgentSpec
    extends TestKit(
      ActorSystem(
        "OtherPersonAgentSpec",
        ConfigFactory.parseString("""
  akka.log-dead-letters = 10
  akka.actor.debug.fsm = true
  akka.loglevel = debug
  """).withFallback(testConfig("test/input/beamville/beam.conf").resolve())
      )
    )
    with BeamHelper
    with FunSpecLike
    with BeforeAndAfterAll
    with MockitoSugar
    with ImplicitSender {

  private implicit val timeout: Timeout = Timeout(60, TimeUnit.SECONDS)
  private val executionConfig: BeamExecutionConfig = setupBeamWithConfig(system.settings.config)
  private lazy val beamConfig = executionConfig.beamConfig
  private lazy val beamScenario = loadScenario(beamConfig)
  private lazy val matsimScenario = buildScenarioFromMatsimConfig(executionConfig.matsimConfig, beamScenario)
  private val injector = buildInjector(system.settings.config, matsimScenario, beamScenario)
  private lazy val beamSvc = buildBeamServices(injector, matsimScenario)

  lazy val eventsManager = new EventsManagerImpl()

  lazy val dummyAgentId: Id[Person] = Id.createPersonId("dummyAgent")

  lazy val householdsFactory: HouseholdsFactoryImpl = new HouseholdsFactoryImpl()

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

    override def utilityOf(
      mode: BeamMode,
      cost: Double,
      time: Double,
      numTransfers: Int
    ): Double = 0.0

    override def computeAllDayUtility(
      trips: ListBuffer[EmbodiedBeamTrip],
      person: Person,
      attributesOfIndividual: AttributesOfIndividual
    ): Double = 0.0
  }

  private lazy val parkingManager = system.actorOf(
    ZonalParkingManager.props(beamConfig, beamScenario.tazTreeMap, beamSvc.geo, beamSvc.beamRouter), "ParkingManager"
  )

  private lazy val networkCoordinator = new DefaultNetworkCoordinator(beamConfig)

  describe("A PersonAgent FSM") {
    it("should also work when the first bus is late") {
      val transitDriverProps = Props(new ForwardActor(self))
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

      val busLeg = EmbodiedBeamLeg(
        BeamLeg(
          28800,
          BeamMode.BUS,
          600,
          BeamPath(
            Vector(),
            Vector(),
            Some(TransitStopsInfo(1, busId, 2)),
            SpaceTime(beamSvc.geo.utm2Wgs(new Coord(166321.9, 1568.87)), 28800),
            SpaceTime(beamSvc.geo.utm2Wgs(new Coord(167138.4, 1117)), 29400),
            1.0
          )
        ),
        busId,
        Id.create("Car", classOf[BeamVehicleType]),
        asDriver = false,
        0,
        unbecomeDriverOnCompletion = false
      )
      val busLeg2 = EmbodiedBeamLeg(
        BeamLeg(
          29400,
          BeamMode.BUS,
          600,
          BeamPath(
            Vector(),
            Vector(),
            Some(TransitStopsInfo(2, busId, 3)),
            SpaceTime(beamSvc.geo.utm2Wgs(new Coord(167138.4, 1117)), 29400),
            SpaceTime(beamSvc.geo.utm2Wgs(new Coord(180000.4, 1200)), 30000),
            1.0
          )
        ),
        busId,
        Id.create("Car", classOf[BeamVehicleType]),
        asDriver = false,
        0,
        unbecomeDriverOnCompletion = false
      )
      val tramLeg = EmbodiedBeamLeg(
        BeamLeg(
          30000,
          BeamMode.TRAM,
          600,
          BeamPath(
            Vector(),
            Vector(),
            Some(TransitStopsInfo(3, tramId, 4)),
            SpaceTime(beamSvc.geo.utm2Wgs(new Coord(180000.4, 1200)), 30000),
            SpaceTime(beamSvc.geo.utm2Wgs(new Coord(190000.4, 1300)), 30600),
            1.0
          )
        ),
        tramId,
        Id.create("Car", classOf[BeamVehicleType]),
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
            Some(TransitStopsInfo(3, tramId, 4)),
            SpaceTime(beamSvc.geo.utm2Wgs(new Coord(180000.4, 1200)), 35000),
            SpaceTime(beamSvc.geo.utm2Wgs(new Coord(190000.4, 1300)), 35600),
            1.0
          )
        ),
        tramId,
        Id.create("Car", classOf[BeamVehicleType]),
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

      val householdActor = TestActorRef[HouseholdActor](
        new HouseholdActor(
          beamSvc,
          beamScenario,
          _ => modeChoiceCalculator,
          scheduler,
          networkCoordinator.transportNetwork,
          new TollCalculator(beamSvc.beamConfig),
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
          new BeamSkimmer(beamScenario, beamSvc.geo),
          new TravelTimeObserved(beamScenario, beamSvc.geo)
        )
      )
      scheduler ! StartSchedule(0)

      expectMsgType[RoutingRequest]
      val personActor = lastSender

      scheduler ! ScheduleTrigger(
        BoardVehicleTrigger(28800, busLeg.beamVehicleId),
        personActor
      )
      scheduler ! ScheduleTrigger(
        AlightVehicleTrigger(34400, busLeg.beamVehicleId),
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
                    SpaceTime(beamSvc.geo.utm2Wgs(new Coord(166321.9, 1568.87)), 28800),
                    SpaceTime(beamSvc.geo.utm2Wgs(new Coord(167138.4, 1117)), 28800),
                    1.0
                  )
                ),
                Id.createVehicleId("body-dummyAgent"),
                Id.create("BODY-TYPE-DEFAULT", classOf[BeamVehicleType]),
                asDriver = true,
                0,
                unbecomeDriverOnCompletion = false
              ),
              busLeg,
              busLeg2,
              tramLeg,
              EmbodiedBeamLeg(
                BeamLeg(
                  30600,
                  BeamMode.WALK,
                  0,
                  BeamPath(
                    Vector(),
                    Vector(),
                    None,
                    SpaceTime(beamSvc.geo.utm2Wgs(new Coord(167138.4, 1117)), 30600),
                    SpaceTime(beamSvc.geo.utm2Wgs(new Coord(167138.4, 1117)), 30600),
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
        requestId = 1
      )

      expectMsgType[ModeChoiceEvent]
      expectMsgType[ActivityEndEvent]
      expectMsgType[PersonDepartureEvent]

      expectMsgType[PersonEntersVehicleEvent]
      expectMsgType[VehicleEntersTrafficEvent]
      expectMsgType[VehicleLeavesTrafficEvent]
      expectMsgType[PathTraversalEvent]

      val reservationRequestBus = expectMsgType[ReservationRequest]

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
      expectMsgType[PersonEntersVehicleEvent]

      //Generating 2 events of PersonCost having 0.0 cost in between PersonEntersVehicleEvent & PersonLeavesVehicleEvent

      val personLeavesVehicleEvent = expectMsgType[PersonLeavesVehicleEvent]
      assert(personLeavesVehicleEvent.getTime == 34400.0)

      expectMsgType[ReplanningEvent]
      expectMsgType[RoutingRequest]
      lastSender ! RoutingResponse(
        Vector(
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
                    SpaceTime(beamSvc.geo.utm2Wgs(new Coord(167138.4, 1117)), 35600),
                    SpaceTime(beamSvc.geo.utm2Wgs(new Coord(167138.4, 1117)), 35600),
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
        1
      )
      expectMsgType[ModeChoiceEvent]

      // Person first does the dummy walk leg
      expectMsgType[VehicleEntersTrafficEvent]
      expectMsgType[VehicleLeavesTrafficEvent]
      expectMsgType[PathTraversalEvent]

      val reservationRequestTram = expectMsgType[ReservationRequest]
      lastSender ! ReservationResponse(
        reservationRequestTram.requestId,
        Right(
          ReserveConfirmInfo(
            tramLeg.beamLeg,
            tramLeg.beamLeg,
            reservationRequestBus.passengerVehiclePersonId,
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
        ),
        TRANSIT
      )

      expectMsgType[PersonEntersVehicleEvent]

      //Generating 2 events of PersonCost having 0.0 cost in between PersonEntersVehicleEvent & PersonLeavesVehicleEvent

      expectMsgType[PersonLeavesVehicleEvent]

      expectMsgType[VehicleEntersTrafficEvent]
      expectMsgType[VehicleLeavesTrafficEvent]

      expectMsgType[PathTraversalEvent]
      expectMsgType[TeleportationArrivalEvent]

      expectMsgType[PersonArrivalEvent]
      expectMsgType[ActivityStartEvent]

      expectMsgType[CompletionNotice]
    }
  }

  override def beforeAll: Unit = {
    eventsManager.addHandler(new BasicEventHandler {
      override def handleEvent(event: Event): Unit = {
        self ! event
      }
    })
    networkCoordinator.loadNetwork()
    networkCoordinator.convertFrequenciesToTrips()
  }

  override def afterAll: Unit = {
    shutdown()
  }
}
