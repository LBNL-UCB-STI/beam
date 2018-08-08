package beam.agentsim.agents

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.testkit.TestActors.ForwardActor
import akka.testkit.{ImplicitSender, TestActorRef, TestKit}
import akka.util.Timeout
import beam.agentsim.agents.household.HouseholdActor.HouseholdActor
import beam.agentsim.agents.modalBehaviors.DrivesVehicle.{NotifyLegEndTrigger, NotifyLegStartTrigger}
import beam.agentsim.agents.modalBehaviors.ModeChoiceCalculator
import beam.agentsim.agents.vehicles.AccessErrorCodes.VehicleGoneError
import beam.agentsim.agents.vehicles.BeamVehicleType.Car
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.{BeamVehicle, ReservationRequest, ReservationResponse, ReserveConfirmInfo}
import beam.agentsim.events.{ModeChoiceEvent, PathTraversalEvent, SpaceTime}
import beam.agentsim.scheduler.BeamAgentScheduler
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger, SchedulerProps, StartSchedule}
import beam.router.BeamRouter.{RoutingRequest, RoutingResponse}
import beam.router.Modes
import beam.router.Modes.BeamMode
import beam.router.RoutingModel.{EmbodiedBeamLeg, _}
import beam.router.r5.NetworkCoordinator
import beam.sim.BeamServices
import beam.sim.common.GeoUtilsImpl
import beam.sim.config.BeamConfig
import beam.utils.TestConfigUtils.testConfig
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

import scala.collection.concurrent.TrieMap
import scala.collection.{JavaConverters, mutable}
import scala.concurrent.Await

/**
  * Created by sfeygin on 2/7/17.
  */
class OtherPersonAgentSpec extends TestKit(ActorSystem("testsystem", ConfigFactory.parseString(
  """
  akka.log-dead-letters = 10
  akka.actor.debug.fsm = true
  akka.loglevel = debug
  """).withFallback(testConfig("test/input/beamville/beam.conf")))) with FunSpecLike
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

  // Mock a transit driver (who has to be a child of a mock router)
  val transitDriverProps = Props(new ForwardActor(self))
  val router = system.actorOf(Props(new Actor() {
    context.actorOf(transitDriverProps, "TransitDriverAgent-my_bus")
    context.actorOf(transitDriverProps, "TransitDriverAgent-my_tram")
    override def receive: Receive = {
      case _ =>
    }
  }), "router")

  private val networkCoordinator = new NetworkCoordinator(config, VehicleUtils.createVehiclesContainer())
  networkCoordinator.loadNetwork()

  describe("A PersonAgent FSM") {

    it("should also work when the first bus is late") {
      val vehicleType = new VehicleTypeImpl(Id.create(1, classOf[VehicleType]))
      val bus = new BeamVehicle(new Powertrain(0.0), new VehicleImpl(Id.createVehicleId("my_bus"), vehicleType), None, Car)
      val tram = new BeamVehicle(new Powertrain(0.0), new VehicleImpl(Id.createVehicleId("my_tram"), vehicleType), None, Car)

      vehicles.put(bus.getId, bus)
      vehicles.put(tram.getId, tram)

      val busLeg = EmbodiedBeamLeg(BeamLeg(28800, BeamMode.BUS, 600, BeamPath(Vector(), Some(TransitStopsInfo(1, Id.createVehicleId("my_bus"), 2)), SpaceTime(new Coord(166321.9,1568.87), 28800), SpaceTime(new Coord(167138.4,1117), 29400), 1.0)), Id.createVehicleId("my_bus"), false, None, BigDecimal(0), false)
      val busLeg2 = EmbodiedBeamLeg(BeamLeg(29400, BeamMode.BUS, 600, BeamPath(Vector(), Some(TransitStopsInfo(2, Id.createVehicleId("my_bus"), 3)), SpaceTime(new Coord(167138.4,1117), 29400), SpaceTime(new Coord(180000.4,1200), 30000), 1.0)), Id.createVehicleId("my_bus"), false, None, BigDecimal(0), false)
      val tramLeg = EmbodiedBeamLeg(BeamLeg(30000, BeamMode.TRAM, 600, BeamPath(Vector(), Some(TransitStopsInfo(3, Id.createVehicleId("my_tram"), 4)), SpaceTime(new Coord(180000.4,1200), 30000), SpaceTime(new Coord(190000.4,1300), 30600), 1.0)), Id.createVehicleId("my_tram"), false, None, BigDecimal(0), false)
      val replannedTramLeg = EmbodiedBeamLeg(BeamLeg(35000, BeamMode.TRAM, 600, BeamPath(Vector(), Some(TransitStopsInfo(3, Id.createVehicleId("my_tram"), 4)), SpaceTime(new Coord(180000.4,1200), 35000), SpaceTime(new Coord(190000.4,1300), 35600), 1.0)), Id.createVehicleId("my_tram"), false, None, BigDecimal(0), false)

      val household = householdsFactory.createHousehold(Id.create("dummy", classOf[Household]))
      val population = PopulationUtils.createPopulation(ConfigUtils.createConfig())
      val person = PopulationUtils.getFactory.createPerson(Id.createPersonId("dummyAgent"))
      val plan = PopulationUtils.getFactory.createPlan()
      val homeActivity = PopulationUtils.createActivityFromCoord("home", new Coord(166321.9,1568.87))
      homeActivity.setEndTime(28800) // 8:00:00 AM
      plan.addActivity(homeActivity)
      val leg = PopulationUtils.createLeg("walk_transit")
      val route = RouteUtils.createLinkNetworkRouteImpl(Id.createLinkId(1), Array[Id[Link]](), Id.createLinkId(2))
      leg.setRoute(route)
      plan.addLeg(leg)
      val workActivity = PopulationUtils.createActivityFromCoord("work", new Coord(167138.4,1117))
      workActivity.setEndTime(61200) //5:00:00 PM
      plan.addActivity(workActivity)
      person.addPlan(plan)
      population.addPerson(person)
      household.setMemberIds(JavaConverters.bufferAsJavaList(mutable.Buffer(person.getId)))

      val agentsim = config.beam.agentsim
      val newAgentsim = config.beam.agentsim.copy(agentsim.agents,10.0,agentsim.numAgents,agentsim.simulationName,1000000.0,agentsim.taz,agentsim.thresholdForMakingParkingChoiceInMeters,agentsim.thresholdForWalkingInMeters,agentsim.tuning)
      val newConfig = config.copy(config.beam.copy(newAgentsim))


      val scheduler = TestActorRef[BeamAgentScheduler](SchedulerProps(newConfig))

      bus.becomeDriver(Await.result(system.actorSelection("/user/router/TransitDriverAgent-my_bus").resolveOne(), timeout.duration))
      tram.becomeDriver(Await.result(system.actorSelection("/user/router/TransitDriverAgent-my_tram").resolveOne(), timeout.duration))

      val householdActor = TestActorRef[HouseholdActor](new HouseholdActor(services, (_) => modeChoiceCalculator, scheduler, networkCoordinator.transportNetwork, self, self, null,eventsManager, population, household.getId, household, Map(), new Coord(0.0, 0.0)))
      val personActor = householdActor.getSingleChild(person.getId.toString)
      scheduler ! StartSchedule(0)

      val request = expectMsgType[RoutingRequest]
      lastSender ! RoutingResponse(Vector(EmbodiedBeamTrip(Vector(
        EmbodiedBeamLeg(BeamLeg(28800, BeamMode.WALK, 0, BeamPath(Vector(), None, SpaceTime(new Coord(166321.9,1568.87), 28800), SpaceTime(new Coord(167138.4,1117), 28800), 1.0)), Id.createVehicleId("body-dummyAgent"), true, None, BigDecimal(0), false),
        busLeg,
        busLeg2,
        tramLeg,
        EmbodiedBeamLeg(BeamLeg(30600, BeamMode.WALK, 0, BeamPath(Vector(), None, SpaceTime(new Coord(167138.4,1117), 30600), SpaceTime(new Coord(167138.4,1117), 30600), 1.0)), Id.createVehicleId("body-dummyAgent"), true, None, BigDecimal(0), false)
      ))))

      expectMsgType[ModeChoiceEvent]
      expectMsgType[ActivityEndEvent]
      expectMsgType[PersonDepartureEvent]

      expectMsgType[PersonEntersVehicleEvent]
      expectMsgType[VehicleEntersTrafficEvent]
      expectMsgType[VehicleLeavesTrafficEvent]
      expectMsgType[PathTraversalEvent]

      val reservationRequestBus = expectMsgType[ReservationRequest]
      lastSender ! ReservationResponse(reservationRequestBus.requestId, Right(ReserveConfirmInfo(busLeg.beamLeg, busLeg2.beamLeg, reservationRequestBus.passengerVehiclePersonId)))
      scheduler ! ScheduleTrigger(NotifyLegStartTrigger(28800, busLeg.beamLeg), personActor)
      scheduler ! ScheduleTrigger(NotifyLegEndTrigger(29400, busLeg.beamLeg), personActor)
      scheduler ! ScheduleTrigger(NotifyLegStartTrigger(29400, busLeg2.beamLeg), personActor)
      scheduler ! ScheduleTrigger(NotifyLegEndTrigger(34400, busLeg2.beamLeg), personActor)
      expectMsgType[PersonEntersVehicleEvent]
      val personLeavesVehicleEvent = expectMsgType[PersonLeavesVehicleEvent]
      assert(personLeavesVehicleEvent.getTime == 34400.0)

      val reservationRequestLateTram = expectMsgType[ReservationRequest]
      lastSender ! ReservationResponse(reservationRequestLateTram.requestId, Left(VehicleGoneError))

      val replanningRequest = expectMsgType[RoutingRequest]
      lastSender ! RoutingResponse(Vector(EmbodiedBeamTrip(Vector(
        replannedTramLeg,
        EmbodiedBeamLeg(BeamLeg(35600, BeamMode.WALK, 0, BeamPath(Vector(), None, SpaceTime(new Coord(167138.4,1117), 35600), SpaceTime(new Coord(167138.4,1117), 35600), 1.0)), Id.createVehicleId("body-dummyAgent"), true, None, BigDecimal(0), false)
      ))))
      expectMsgType[ModeChoiceEvent]

      val reservationRequestTram = expectMsgType[ReservationRequest]
      lastSender ! ReservationResponse(reservationRequestTram.requestId, Right(ReserveConfirmInfo(tramLeg.beamLeg, tramLeg.beamLeg, reservationRequestBus.passengerVehiclePersonId)))
      scheduler ! ScheduleTrigger(NotifyLegStartTrigger(35000, replannedTramLeg.beamLeg), personActor)
      scheduler ! ScheduleTrigger(NotifyLegEndTrigger(40000, replannedTramLeg.beamLeg), personActor) // My tram is late!
      expectMsgType[PersonEntersVehicleEvent]
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


  override def afterAll: Unit = {
    shutdown()
  }

}

