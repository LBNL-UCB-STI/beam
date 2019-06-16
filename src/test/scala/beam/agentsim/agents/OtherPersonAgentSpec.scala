package beam.agentsim.agents

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.testkit.TestActors.ForwardActor
import akka.testkit.{ImplicitSender, TestActorRef, TestKit}
import akka.util.Timeout
import beam.agentsim.agents.PersonTestUtil._
import beam.agentsim.agents.choice.mode.ModeIncentive
import beam.agentsim.agents.choice.mode.ModeIncentive.Incentive
import beam.agentsim.agents.household.HouseholdActor.HouseholdActor
import beam.agentsim.agents.modalbehaviors.DrivesVehicle.{AlightVehicleTrigger, BoardVehicleTrigger}
import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.{BeamVehicle, ReservationRequest, ReservationResponse, ReserveConfirmInfo, _}
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
import beam.sim.BeamServices
import beam.sim.common.GeoUtilsImpl
import beam.sim.config.BeamConfig
import beam.sim.population.AttributesOfIndividual
import beam.utils.StuckFinder
import beam.utils.TestConfigUtils.testConfig
import com.google.inject.{AbstractModule, Guice, Injector}
import com.typesafe.config.ConfigFactory
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.events._
import org.matsim.api.core.v01.network.{Link, Network}
import org.matsim.api.core.v01.population.{Activity, Person}
import org.matsim.api.core.v01.{Coord, Id, Scenario}
import org.matsim.core.api.experimental.events.TeleportationArrivalEvent
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
import scala.collection.mutable.ListBuffer
import scala.collection.{mutable, JavaConverters}
import scala.concurrent.Await

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
    with FunSpecLike
    with BeforeAndAfterAll
    with MockitoSugar
    with beam.utils.InjectableMock
    with ImplicitSender
    with BeamvilleFixtures {

  private implicit val timeout: Timeout = Timeout(60, TimeUnit.SECONDS)
  lazy val beamConfig = BeamConfig(system.settings.config)
  lazy val eventsManager = new EventsManagerImpl()

  lazy val dummyAgentId: Id[Person] = Id.createPersonId("dummyAgent")

  val vehicles: TrieMap[Id[BeamVehicle], BeamVehicle] =
    TrieMap[Id[BeamVehicle], BeamVehicle]()

  lazy val householdsFactory: HouseholdsFactoryImpl = new HouseholdsFactoryImpl()

  lazy val guiceInjector: Injector = Guice.createInjector(new AbstractModule() {
    protected def configure(): Unit = {
      bind(classOf[TravelTimeObserved]).toInstance(mock[TravelTimeObserved])
    }
  })

  lazy val beamSvc: BeamServices = {
    lazy val injector = guiceInjector
    lazy val theServices = mock[BeamServices](withSettings().stubOnly())
    when(theServices.matsimServices).thenReturn(mock[MatsimServices])
    when(theServices.matsimServices.getScenario).thenReturn(mock[Scenario])
    when(theServices.matsimServices.getScenario.getNetwork).thenReturn(mock[Network])
    when(theServices.beamConfig).thenReturn(beamConfig)
    when(theServices.vehicleTypes).thenReturn(Map[Id[BeamVehicleType], BeamVehicleType]())
    when(theServices.modeIncentives).thenReturn(ModeIncentive(Map[BeamMode, List[Incentive]]()))
    when(theServices.vehicleEnergy).thenReturn(mock[VehicleEnergy])
    lazy val geo = new GeoUtilsImpl(beamConfig)
    when(theServices.geo).thenReturn(geo)
    // TODO Is it right to return defaultTazTreeMap?
    when(theServices.tazTreeMap).thenReturn(BeamServices.defaultTazTreeMap)
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

    setupInjectableMock(beamConfig, beamSvc)
  }

  private lazy val parkingManager = system.actorOf(
    ZonalParkingManager
      .props(beamSvc, beamSvc.beamRouter, boundingBox),
    "ParkingManager"
  )

  private lazy val networkCoordinator = new DefaultNetworkCoordinator(beamConfig)

  describe("A PersonAgent FSM") {
    it("should also work when the first bus is late") {
      val mockDriverProps = Props(new ForwardActor(self))
      val router: ActorRef = system.actorOf(
        Props(new Actor() {
          context.actorOf(mockDriverProps, "TransitDriverAgent-my_bus")
          context.actorOf(mockDriverProps, "TransitDriverAgent-my_tram")
          override def receive: Receive = {
            case _ =>
          }
        }),
        "router"
      )

      val beamVehicleId = Id.createVehicleId("my_bus")

      val bus = new BeamVehicle(
        beamVehicleId,
        new Powertrain(0.0),
        BeamVehicleType.defaultCarBeamVehicleType
      )
      val tram = new BeamVehicle(
        Id.createVehicleId("my_tram"),
        new Powertrain(0.0),
        BeamVehicleType.defaultCarBeamVehicleType
      )

      vehicles.put(bus.id, bus)
      vehicles.put(tram.id, tram)

      val busLeg = EmbodiedBeamLeg(
        BeamLeg(
          28800,
          BeamMode.BUS,
          600,
          BeamPath(
            Vector(),
            Vector(),
            Some(TransitStopsInfo(1, Id.createVehicleId("my_bus"), 2)),
            SpaceTime(beamSvc.geo.utm2Wgs(new Coord(166321.9, 1568.87)), 28800),
            SpaceTime(beamSvc.geo.utm2Wgs(new Coord(167138.4, 1117)), 29400),
            1.0
          )
        ),
        Id.createVehicleId("my_bus"),
        BeamVehicleType.defaultCarBeamVehicleType.id,
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
            Some(TransitStopsInfo(2, Id.createVehicleId("my_bus"), 3)),
            SpaceTime(beamSvc.geo.utm2Wgs(new Coord(167138.4, 1117)), 29400),
            SpaceTime(beamSvc.geo.utm2Wgs(new Coord(180000.4, 1200)), 30000),
            1.0
          )
        ),
        Id.createVehicleId("my_bus"),
        BeamVehicleType.defaultCarBeamVehicleType.id,
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
            Some(TransitStopsInfo(3, Id.createVehicleId("my_tram"), 4)),
            SpaceTime(beamSvc.geo.utm2Wgs(new Coord(180000.4, 1200)), 30000),
            SpaceTime(beamSvc.geo.utm2Wgs(new Coord(190000.4, 1300)), 30600),
            1.0
          )
        ),
        Id.createVehicleId("my_tram"),
        BeamVehicleType.defaultCarBeamVehicleType.id,
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
            Some(TransitStopsInfo(3, Id.createVehicleId("my_tram"), 4)),
            SpaceTime(beamSvc.geo.utm2Wgs(new Coord(180000.4, 1200)), 35000),
            SpaceTime(beamSvc.geo.utm2Wgs(new Coord(190000.4, 1300)), 35600),
            1.0
          )
        ),
        Id.createVehicleId("my_tram"),
        BeamVehicleType.defaultCarBeamVehicleType.id,
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

      bus.becomeDriver(
        Await.result(
          system
            .actorSelection("/user/router/TransitDriverAgent-my_bus")
            .resolveOne(),
          timeout.duration
        )
      )
      tram.becomeDriver(
        Await.result(
          system
            .actorSelection("/user/router/TransitDriverAgent-my_tram")
            .resolveOne(),
          timeout.duration
        )
      )

      val householdActor = TestActorRef[HouseholdActor](
        new HouseholdActor(
          beamSvc,
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
          new BeamSkimmer(beamConfig, beamSvc),
          boundingBox
        )
      )
      scheduler ! StartSchedule(0)

      expectMsgType[RoutingRequest]
      val personActor = lastSender

      scheduler ! ScheduleTrigger(
        BoardVehicleTrigger(28800, busLeg.beamVehicleId, Some(BeamVehicleType.defaultHumanBodyBeamVehicleType.id)),
        personActor
      )
      scheduler ! ScheduleTrigger(
        AlightVehicleTrigger(34400, busLeg.beamVehicleId, Some(BeamVehicleType.defaultHumanBodyBeamVehicleType.id)),
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
                BeamVehicleType.defaultHumanBodyBeamVehicleType.id,
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
                BeamVehicleType.defaultHumanBodyBeamVehicleType.id,
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
                BeamVehicleType.defaultHumanBodyBeamVehicleType.id,
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
                  replannedTramLeg.beamVehicleId,
                  Some(BeamVehicleType.defaultHumanBodyBeamVehicleType.id)
                ),
                personActor
              ),
              ScheduleTrigger(
                AlightVehicleTrigger(
                  40000,
                  replannedTramLeg.beamVehicleId,
                  Some(BeamVehicleType.defaultHumanBodyBeamVehicleType.id)
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
