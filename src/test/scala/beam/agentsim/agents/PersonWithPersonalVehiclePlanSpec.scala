package beam.agentsim.agents

import akka.actor.{ActorSystem, Props}
import akka.pattern.ask
import akka.testkit.{ImplicitSender, TestActorRef, TestKitBase, TestProbe}
import akka.util.Timeout

import beam.agentsim.agents.PersonTestUtil._
import beam.agentsim.agents.choice.mode.ModeChoiceUniformRandom
import beam.agentsim.agents.household.HouseholdActor.HouseholdActor
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.{BeamVehicle, _}
import beam.agentsim.events._
import beam.agentsim.infrastructure.{
  AnotherTrivialParkingManager,
  ParkingInquiry,
  ParkingInquiryResponse,
  TrivialParkingManager
}
import beam.agentsim.scheduler.BeamAgentScheduler
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger, SchedulerProps, StartSchedule}
import beam.router.BeamRouter._
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{BIKE, CAR, WALK}
import beam.router.RouteHistory
import beam.router.model.{EmbodiedBeamLeg, _}
import beam.router.skim.core.AbstractSkimmerEvent
import beam.sim.vehicles.VehiclesAdjustment
import beam.utils.TestConfigUtils.testConfig
import beam.utils.{SimRunnerForTest, StuckFinder, TestConfigUtils}
import com.typesafe.config.{Config, ConfigFactory}
import org.matsim.api.core.v01.events._
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.api.experimental.events.TeleportationArrivalEvent
import org.matsim.core.config.ConfigUtils
import org.matsim.core.events.EventsManagerImpl
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.population.PopulationUtils
import org.matsim.core.population.routes.RouteUtils
import org.matsim.households.{Household, HouseholdsFactoryImpl, Income, IncomeImpl}
import org.matsim.vehicles._
import org.scalatest.matchers.should.Matchers._
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}
import org.scalatest.funspec.AnyFunSpecLike

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import scala.collection.{mutable, JavaConverters}
import scala.concurrent.ExecutionContext

class PersonWithPersonalVehiclePlanSpec
    extends AnyFunSpecLike
    with TestKitBase
    with SimRunnerForTest
    with BeforeAndAfterAll
    with BeforeAndAfter
    with ImplicitSender
    with BeamvilleFixtures {

  lazy val config: Config = ConfigFactory
    .parseString(
      """
        akka.log-dead-letters = 10
        akka.actor.debug.fsm = true
        akka.loglevel = debug
        akka.test.timefactor = 2
        beam.agentsim.agents.vehicles.generateEmergencyHouseholdVehicleWhenPlansRequireIt = true
        """
    )
    .withFallback(testConfig("test/input/beamville/beam.conf"))
    .resolve()

  lazy implicit val system: ActorSystem = ActorSystem("PersonWithPersonalVehiclePlanSpec", config)
  private implicit val timeout: Timeout = Timeout(60, TimeUnit.SECONDS)
  private implicit val executionContext: ExecutionContext = system.dispatcher

  override def outputDirPath: String = TestConfigUtils.testOutputDir

  private val householdsFactory: HouseholdsFactoryImpl = new HouseholdsFactoryImpl()

  private lazy val modeChoiceCalculator = new ModeChoiceUniformRandom(beamConfig)

  val homeLocation = new Coord(170308.4, 2964.6474)
  val workLocation = new Coord(169346.4, 876.7536)

  describe("A PersonAgent") {

    val hoseHoldDummyId = Id.create("dummy", classOf[Household])

    it("should know how to take a car trip when it's already in its plan") {
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
      val vehicleId = Id.createVehicleId("car-dummyAgent")
      val vehicleType = beamScenario.vehicleTypes(Id.create("beamVilleCar", classOf[BeamVehicleType]))
      val beamVehicle = new BeamVehicle(vehicleId, new Powertrain(0.0), vehicleType)

      val household = householdsFactory.createHousehold(hoseHoldDummyId)
      val population = PopulationUtils.createPopulation(ConfigUtils.createConfig())

      val person: Person = createTestPerson(Id.createPersonId("dummyAgent"), vehicleId, CAR)
      population.addPerson(person)

      household.setMemberIds(JavaConverters.bufferAsJavaList(mutable.Buffer(person.getId)))

      val scheduler = TestActorRef[BeamAgentScheduler](
        SchedulerProps(
          beamConfig,
          stopTick = 24 * 60 * 60,
          maxWindow = 10,
          new StuckFinder(beamConfig.beam.debug.stuckAgentDetection)
        )
      )
      val parkingLocation = new Coord(167138.4, 1117.0)
      val parkingManager = system.actorOf(Props(new AnotherTrivialParkingManager(parkingLocation)))
      //val chargingNetworkManager = system.actorOf(Props(new ChargingNetworkManager(services, beamScenario, scheduler)))

      val householdActor = TestActorRef[HouseholdActor](
        Props(
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
            self,
            eventsManager,
            population,
            household,
            Map(beamVehicle.id -> beamVehicle),
            new Coord(0.0, 0.0),
            Vector(),
            Set.empty,
            new RouteHistory(beamConfig),
            VehiclesAdjustment.getVehicleAdjustment(beamScenario),
            configHolder
          )
        )
      )
      scheduler ! ScheduleTrigger(InitializeTrigger(0), householdActor)

      scheduler ! StartSchedule(0)

      // Agent will first choose their tour mode before doing detailed routing
      expectMsgType[TourModeChoiceEvent]

      // The agent will ask for current travel times for a route it already knows.
      val embodyRequest = expectMsgType[EmbodyWithCurrentTravelTime]
      assert(services.geo.wgs2Utm(embodyRequest.leg.travelPath.startPoint.loc).getX === homeLocation.getX +- 1)
      assert(services.geo.wgs2Utm(embodyRequest.leg.travelPath.endPoint.loc).getY === workLocation.getY +- 1)
      lastSender ! RoutingResponse(
        Vector(
          EmbodiedBeamTrip(
            legs = Vector(
              EmbodiedBeamLeg(
                beamLeg = embodyRequest.leg.copy(
                  duration = 500,
                  travelPath = embodyRequest.leg.travelPath
                    .copy(
                      linkTravelTime = embodyRequest.leg.travelPath.linkIds.map(_ => 50.0),
                      endPoint = embodyRequest.leg.travelPath.endPoint
                        .copy(time = embodyRequest.leg.startTime + (embodyRequest.leg.travelPath.linkIds.size - 1) * 50)
                    )
                ),
                beamVehicleId = vehicleId,
                Id.create("TRANSIT-TYPE-DEFAULT", classOf[BeamVehicleType]),
                asDriver = true,
                cost = 0.0,
                unbecomeDriverOnCompletion = true
              )
            )
          )
        ),
        requestId = 1,
        request = None,
        isEmbodyWithCurrentTravelTime = false,
        triggerId = embodyRequest.triggerId
      )

      expectMsgType[ModeChoiceEvent]
      expectMsgType[ActivityEndEvent]
      expectMsgType[BeamPersonDepartureEvent]

      expectMsgType[PersonEntersVehicleEvent]
      expectMsgType[VehicleEntersTrafficEvent]
      expectMsgType[VehicleLeavesTrafficEvent]
      expectMsgType[PathTraversalEvent]

      expectMsgType[PersonEntersVehicleEvent]
      expectMsgType[LeavingParkingEvent]
      expectMsgType[VehicleEntersTrafficEvent]
      expectMsgType[LinkLeaveEvent]
      expectMsgType[LinkEnterEvent]
      expectMsgType[LinkLeaveEvent]
      expectMsgType[LinkEnterEvent]
      expectMsgType[LinkLeaveEvent]
      expectMsgType[LinkEnterEvent]
      expectMsgType[LinkLeaveEvent]
      expectMsgType[LinkEnterEvent]
      expectMsgType[LinkLeaveEvent]
      expectMsgType[LinkEnterEvent]
      expectMsgType[VehicleLeavesTrafficEvent]
      expectMsgType[PathTraversalEvent]

      val parkingRoutingRequest = expectMsgType[RoutingRequest]
      assert(parkingRoutingRequest.destinationUTM == parkingLocation)
      lastSender ! RoutingResponse(
        itineraries = Vector(
          EmbodiedBeamTrip(
            legs = Vector(
              EmbodiedBeamLeg(
                beamLeg = BeamLeg(
                  startTime = parkingRoutingRequest.departureTime,
                  mode = BeamMode.CAR,
                  duration = 50,
                  travelPath = BeamPath(
                    linkIds = Array(142, 60, 58, 62, 80),
                    linkTravelTime = Array(50, 50, 50, 50, 50),
                    transitStops = None,
                    startPoint = SpaceTime(
                      services.geo.utm2Wgs(parkingRoutingRequest.originUTM),
                      parkingRoutingRequest.departureTime
                    ),
                    endPoint =
                      SpaceTime(services.geo.utm2Wgs(parkingLocation), parkingRoutingRequest.departureTime + 200),
                    distanceInM = 1000d
                  )
                ),
                beamVehicleId = Id.createVehicleId("car-1"),
                Id.create("TRANSIT-TYPE-DEFAULT", classOf[BeamVehicleType]),
                asDriver = true,
                cost = 0.0,
                unbecomeDriverOnCompletion = true
              )
            )
          )
        ),
        requestId = parkingRoutingRequest.requestId,
        request = None,
        isEmbodyWithCurrentTravelTime = false,
        triggerId = parkingRoutingRequest.triggerId
      )

      val walkFromParkingRoutingRequest = expectMsgType[RoutingRequest]
      assert(walkFromParkingRoutingRequest.originUTM.getX === parkingLocation.getX +- 1)
      assert(walkFromParkingRoutingRequest.originUTM.getY === parkingLocation.getY +- 1)
      assert(walkFromParkingRoutingRequest.destinationUTM.getX === workLocation.getX +- 1)
      assert(walkFromParkingRoutingRequest.destinationUTM.getY === workLocation.getY +- 1)
      lastSender ! RoutingResponse(
        itineraries = Vector(
          EmbodiedBeamTrip(
            legs = Vector(
              EmbodiedBeamLeg(
                beamLeg = BeamLeg(
                  startTime = walkFromParkingRoutingRequest.departureTime,
                  mode = BeamMode.WALK,
                  duration = 50,
                  travelPath = BeamPath(
                    linkIds = Array(80, 62, 58, 60, 142),
                    linkTravelTime = Array(50, 50, 50, 50, 50),
                    transitStops = None,
                    startPoint =
                      SpaceTime(services.geo.utm2Wgs(parkingLocation), walkFromParkingRoutingRequest.departureTime),
                    endPoint = SpaceTime(
                      services.geo.utm2Wgs(walkFromParkingRoutingRequest.destinationUTM),
                      walkFromParkingRoutingRequest.departureTime + 200
                    ),
                    distanceInM = 1000d
                  )
                ),
                beamVehicleId = walkFromParkingRoutingRequest.streetVehicles.find(_.mode == WALK).get.id,
                walkFromParkingRoutingRequest.streetVehicles.find(_.mode == WALK).get.vehicleTypeId,
                asDriver = true,
                cost = 0.0,
                unbecomeDriverOnCompletion = true
              )
            )
          )
        ),
        requestId = parkingRoutingRequest.requestId,
        request = None,
        isEmbodyWithCurrentTravelTime = false,
        triggerId = walkFromParkingRoutingRequest.triggerId
      )

      expectMsgType[LeavingParkingEvent]
      expectMsgType[VehicleEntersTrafficEvent]
      expectMsgType[LinkLeaveEvent]
      expectMsgType[LinkEnterEvent]
      expectMsgType[LinkLeaveEvent]
      expectMsgType[LinkEnterEvent]
      expectMsgType[LinkLeaveEvent]
      expectMsgType[LinkEnterEvent]
      expectMsgType[LinkLeaveEvent]
      expectMsgType[LinkEnterEvent]
      expectMsgType[VehicleLeavesTrafficEvent]
      expectMsgType[PathTraversalEvent]
      expectMsgType[ParkingEvent]
      expectMsgType[PersonCostEvent]
      expectMsgType[PersonLeavesVehicleEvent]

      expectMsgType[VehicleEntersTrafficEvent]
      expectMsgType[VehicleLeavesTrafficEvent]
      expectMsgType[PathTraversalEvent]

      expectMsgType[PersonLeavesVehicleEvent]
      expectMsgType[TeleportationArrivalEvent]

      expectMsgType[PersonArrivalEvent]
      expectMsgType[ActivityStartEvent]

      expectMsgType[CompletionNotice]
    }

    it("should know how to take a bicycle trip when it's already in its plan") {
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
      val vehicleId = Id.createVehicleId("bicycle-dummyAgent")
      val vehicleType = beamScenario.vehicleTypes(Id.create("Bicycle", classOf[BeamVehicleType]))
      val beamVehicle =
        new BeamVehicle(
          vehicleId,
          new Powertrain(0.0),
          vehicleType,
          vehicleManagerId = new AtomicReference(VehicleManager.NoManager.managerId)
        )

      val household = householdsFactory.createHousehold(hoseHoldDummyId)
      val population = PopulationUtils.createPopulation(ConfigUtils.createConfig())

      val person: Person = createTestPerson(Id.createPersonId("dummyAgent"), vehicleId, BIKE)
      population.addPerson(person)

      household.setMemberIds(JavaConverters.bufferAsJavaList(mutable.Buffer(person.getId)))

      val scheduler = TestActorRef[BeamAgentScheduler](
        SchedulerProps(
          beamConfig,
          stopTick = 24 * 60 * 60,
          maxWindow = 10,
          new StuckFinder(beamConfig.beam.debug.stuckAgentDetection)
        )
      )
      val parkingManager = system.actorOf(Props(new TrivialParkingManager()))
      //val chargingNetworkManager = system.actorOf(Props(new ChargingNetworkManager(services, beamScenario, scheduler)))

      val householdActor = TestActorRef[HouseholdActor](
        Props(
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
            self,
            eventsManager,
            population,
            household,
            Map(beamVehicle.id -> beamVehicle),
            new Coord(0.0, 0.0),
            Vector(),
            Set.empty,
            new RouteHistory(beamConfig),
            VehiclesAdjustment.getVehicleAdjustment(beamScenario),
            configHolder
          )
        )
      )
      scheduler ! ScheduleTrigger(InitializeTrigger(0), householdActor)

      scheduler ! StartSchedule(0)

      expectMsgType[TourModeChoiceEvent]

      // The agent will ask for current travel times for a route it already knows.
      val embodyRequest = expectMsgType[EmbodyWithCurrentTravelTime]
      assert(services.geo.wgs2Utm(embodyRequest.leg.travelPath.startPoint.loc).getX === homeLocation.getX +- 1)
      assert(services.geo.wgs2Utm(embodyRequest.leg.travelPath.endPoint.loc).getY === workLocation.getY +- 1)
      lastSender ! RoutingResponse(
        itineraries = Vector(
          EmbodiedBeamTrip(
            legs = Vector(
              EmbodiedBeamLeg(
                beamLeg = embodyRequest.leg.copy(
                  duration = 500,
                  travelPath = embodyRequest.leg.travelPath
                    .copy(
                      linkTravelTime = embodyRequest.leg.travelPath.linkIds.map(_ => 50.0),
                      endPoint = embodyRequest.leg.travelPath.endPoint
                        .copy(time = embodyRequest.leg.startTime + (embodyRequest.leg.travelPath.linkIds.size - 1) * 50)
                    )
                ),
                beamVehicleId = vehicleId,
                Id.create("TRANSIT-TYPE-DEFAULT", classOf[BeamVehicleType]),
                asDriver = true,
                cost = 0.0,
                unbecomeDriverOnCompletion = true
              )
            )
          )
        ),
        requestId = 1,
        request = None,
        isEmbodyWithCurrentTravelTime = false,
        triggerId = embodyRequest.triggerId
      )

      expectMsgType[ModeChoiceEvent]
      expectMsgType[ActivityEndEvent]
      expectMsgType[BeamPersonDepartureEvent]

      expectMsgType[PersonEntersVehicleEvent]
      expectMsgType[VehicleEntersTrafficEvent]
      expectMsgType[VehicleLeavesTrafficEvent]
      expectMsgType[PathTraversalEvent]

      expectMsgType[PersonEntersVehicleEvent]
      expectMsgType[VehicleEntersTrafficEvent]
      expectMsgType[LinkLeaveEvent]
      expectMsgType[LinkEnterEvent]
      expectMsgType[LinkLeaveEvent]
      expectMsgType[LinkEnterEvent]
      expectMsgType[LinkLeaveEvent]
      expectMsgType[LinkEnterEvent]
      expectMsgType[LinkLeaveEvent]
      expectMsgType[LinkEnterEvent]
      expectMsgType[LinkLeaveEvent]
      expectMsgType[LinkEnterEvent]
      expectMsgType[LinkLeaveEvent]
      expectMsgType[LinkEnterEvent]
      expectMsgType[VehicleLeavesTrafficEvent]
      expectMsgType[PathTraversalEvent]
      expectMsgType[PersonLeavesVehicleEvent]

      expectMsgType[VehicleEntersTrafficEvent]
      expectMsgType[VehicleLeavesTrafficEvent]
      expectMsgType[PathTraversalEvent]

      expectMsgType[PersonLeavesVehicleEvent]
      expectMsgType[TeleportationArrivalEvent]

      expectMsgType[PersonArrivalEvent]
      expectMsgType[ActivityStartEvent]

      expectMsgType[CompletionNotice]
    }

    it("should use another car when the car that is in the plan is taken") {
      val modeChoiceEvents = new TestProbe(system)
      val tourModeChoiceEvents = new TestProbe(system)
      val personEntersVehicleEvents = new TestProbe(system)
      val eventsManager = new EventsManagerImpl()
      eventsManager.addHandler(
        new BasicEventHandler {
          override def handleEvent(event: Event): Unit = {
            event match {
              case _: AbstractSkimmerEvent     => // ignore
              case _: TourModeChoiceEvent      => tourModeChoiceEvents.ref ! event
              case _: ModeChoiceEvent          => modeChoiceEvents.ref ! event
              case _: PersonEntersVehicleEvent => personEntersVehicleEvents.ref ! event
              case _                           => // ignore
            }
          }
        }
      )
      val vehicleType = beamScenario.vehicleTypes(Id.create("beamVilleCar", classOf[BeamVehicleType]))
      val car1 = new BeamVehicle(
        Id.createVehicleId("car-1"),
        new Powertrain(0.0),
        vehicleType
      )
      val car2 = new BeamVehicle(
        Id.createVehicleId("car-2"),
        new Powertrain(0.0),
        vehicleType
      )

      val household = householdsFactory.createHousehold(hoseHoldDummyId)
      val population = PopulationUtils.createPopulation(ConfigUtils.createConfig())

      val person: Person = createTestPerson(Id.createPersonId("dummyAgent"), car1.id, CAR)
      population.addPerson(person)
      val otherPerson: Person = createTestPerson(Id.createPersonId("dummyAgent2"), car1.id, CAR)
      population.addPerson(otherPerson)

      household.setMemberIds(JavaConverters.bufferAsJavaList(mutable.Buffer(person.getId, otherPerson.getId)))

      val scheduler = TestActorRef[BeamAgentScheduler](
        SchedulerProps(
          beamConfig,
          stopTick = 24 * 60 * 60,
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
          self,
          eventsManager,
          population,
          household,
          Map(car1.id -> car1, car2.id -> car2),
          new Coord(0.0, 0.0),
          Vector(),
          Set.empty,
          new RouteHistory(beamConfig),
          VehiclesAdjustment.getVehicleAdjustment(beamScenario),
          configHolder
        )
      )
      scheduler ! ScheduleTrigger(InitializeTrigger(0), householdActor)

      scheduler ! StartSchedule(0)

      for (_ <- 0 to 1) {
        expectMsgPF() { case EmbodyWithCurrentTravelTime(leg, vehicleId, _, _, triggerId) =>
          val embodiedLeg = EmbodiedBeamLeg(
            beamLeg = leg.copy(
              duration = 500,
              travelPath = leg.travelPath.copy(
                linkTravelTime = Array(0, 100, 100, 100, 100, 100, 0),
                endPoint = leg.travelPath.endPoint.copy(time = leg.startTime + 500)
              )
            ),
            beamVehicleId = vehicleId,
            Id.create("TRANSIT-TYPE-DEFAULT", classOf[BeamVehicleType]),
            asDriver = true,
            cost = 0.0,
            unbecomeDriverOnCompletion = true
          )
          lastSender ! RoutingResponse(
            itineraries = Vector(EmbodiedBeamTrip(Vector(embodiedLeg))),
            requestId = 1,
            request = None,
            isEmbodyWithCurrentTravelTime = false,
            triggerId = triggerId
          )
        }
      }

      tourModeChoiceEvents.expectMsgType[TourModeChoiceEvent]
      tourModeChoiceEvents.expectMsgType[TourModeChoiceEvent]

      modeChoiceEvents.expectMsgType[ModeChoiceEvent]
      modeChoiceEvents.expectMsgType[ModeChoiceEvent]

      personEntersVehicleEvents.expectMsgType[PersonEntersVehicleEvent]
      personEntersVehicleEvents.expectMsgType[PersonEntersVehicleEvent]
      personEntersVehicleEvents.expectMsgType[PersonEntersVehicleEvent]
      personEntersVehicleEvents.expectMsgType[PersonEntersVehicleEvent]

      expectMsgType[CompletionNotice]
    }

    it("should create a last resort car if told to drive but no cars are available") {
      val modeChoiceEvents = new TestProbe(system)
      val personEntersVehicleEvents = new TestProbe(system)
      val tourModeChoiceEvents = new TestProbe(system)
      val eventsManager = new EventsManagerImpl()
      eventsManager.addHandler(
        new BasicEventHandler {
          override def handleEvent(event: Event): Unit = {
            event match {
              case _: AbstractSkimmerEvent     => // ignore
              case _: TourModeChoiceEvent      => tourModeChoiceEvents.ref ! event
              case _: ModeChoiceEvent          => modeChoiceEvents.ref ! event
              case _: PersonEntersVehicleEvent => personEntersVehicleEvents.ref ! event
              case _                           => // ignore
            }
          }
        }
      )
      val vehicleType = beamScenario.vehicleTypes(Id.create("beamVilleCar", classOf[BeamVehicleType]))
      val car1 = new BeamVehicle(
        Id.createVehicleId("car-1"),
        new Powertrain(0.0),
        vehicleType
      )

      val household = householdsFactory.createHousehold(hoseHoldDummyId)
      val population = PopulationUtils.createPopulation(ConfigUtils.createConfig())

      val person: Person = createTestPerson(Id.createPersonId("dummyAgent"), car1.id, CAR)
      population.addPerson(person)
      val otherPerson: Person = createTestPerson(Id.createPersonId("dummyAgent2"), car1.id, CAR)
      population.addPerson(otherPerson)

      household.setMemberIds(JavaConverters.bufferAsJavaList(mutable.Buffer(person.getId, otherPerson.getId)))
      household.setIncome(new IncomeImpl(40, Income.IncomePeriod.year))

      val scheduler = TestActorRef[BeamAgentScheduler](
        SchedulerProps(
          beamConfig,
          stopTick = 24 * 60 * 60,
          maxWindow = 10,
          new StuckFinder(beamConfig.beam.debug.stuckAgentDetection)
        )
      )
      val parkingManager = system.actorOf(Props(new TrivialParkingManager))
      //val chargingNetworkManager = system.actorOf(Props(new ChargingNetworkManager(services, beamScenario, scheduler)))

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
          self,
          eventsManager,
          population,
          household,
          Map(car1.id -> car1),
          new Coord(0.0, 0.0),
          Vector(),
          Set(vehicleType),
          new RouteHistory(beamConfig),
          VehiclesAdjustment.getVehicleAdjustment(beamScenario),
          configHolder
        )
      )
      scheduler ! ScheduleTrigger(InitializeTrigger(0), householdActor)

      scheduler ! StartSchedule(0)

      val messageResponder: PartialFunction[Any, Any] = {
        case EmbodyWithCurrentTravelTime(leg, vehicleId, _, _, triggerId) =>
          val embodiedLeg = EmbodiedBeamLeg(
            beamLeg = leg.copy(
              duration = 500,
              travelPath = leg.travelPath.copy(
                linkTravelTime = Array(0, 100, 100, 100, 100, 100, 0),
                endPoint = leg.travelPath.endPoint.copy(time = leg.startTime + 500)
              )
            ),
            beamVehicleId = vehicleId,
            Id.create("TRANSIT-TYPE-DEFAULT", classOf[BeamVehicleType]),
            asDriver = true,
            cost = 0.0,
            unbecomeDriverOnCompletion = true
          )
          lastSender ! RoutingResponse(
            itineraries = Vector(EmbodiedBeamTrip(Vector(embodiedLeg))),
            requestId = 1,
            request = None,
            isEmbodyWithCurrentTravelTime = false,
            triggerId = triggerId
          )
        case RoutingRequest(_, _, _, _, _, _, _, _, _, _, triggerId) =>
          lastSender ! RoutingResponse(
            itineraries = Vector(),
            requestId = 1,
            request = None,
            isEmbodyWithCurrentTravelTime = false,
            triggerId = triggerId
          )
        case inq: ParkingInquiry =>
          (parkingManager ? inq).mapTo[ParkingInquiryResponse].map(x => lastSender ! x)
      }

      for (_ <- 0 to 1) {
        expectMsgPF()(messageResponder)
      }

      tourModeChoiceEvents.expectMsgType[TourModeChoiceEvent]
      tourModeChoiceEvents.expectMsgType[TourModeChoiceEvent]

      modeChoiceEvents.expectMsgType[ModeChoiceEvent]
      expectMsgPF()(messageResponder)
      expectMsgPF()(messageResponder)
      modeChoiceEvents.expectMsgType[ModeChoiceEvent]
//      expectMsgPF()(messageResponder)

      personEntersVehicleEvents.expectMsgType[PersonEntersVehicleEvent]
      personEntersVehicleEvents.expectMsgType[PersonEntersVehicleEvent]
      personEntersVehicleEvents.expectMsgType[PersonEntersVehicleEvent]

      expectMsgType[CompletionNotice]
    }

    it("should walk to a car that is far away (if told so by the router") {
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
      val vehicleId = Id.createVehicleId("car-1")
      val vehicleType = beamScenario.vehicleTypes(Id.create("beamVilleCar", classOf[BeamVehicleType]))
      val beamVehicle =
        new BeamVehicle(vehicleId, new Powertrain(0.0), vehicleType)
      val household = householdsFactory.createHousehold(hoseHoldDummyId)
      val population = PopulationUtils.createPopulation(ConfigUtils.createConfig())

      val person: Person = createTestPerson(Id.createPersonId("dummyAgent"), vehicleId, CAR, false)
      population.addPerson(person)

      household.setMemberIds(JavaConverters.bufferAsJavaList(mutable.Buffer(person.getId)))

      val scheduler = TestActorRef[BeamAgentScheduler](
        SchedulerProps(
          beamConfig,
          stopTick = 24 * 60 * 60,
          maxWindow = 10,
          new StuckFinder(beamConfig.beam.debug.stuckAgentDetection)
        )
      )
      val parkingManager = system.actorOf(Props(new TrivialParkingManager))
      //val chargingNetworkManager = system.actorOf(Props(new ChargingNetworkManager(services, beamScenario, scheduler)))

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
          self,
          eventsManager,
          population,
          household,
          Map(beamVehicle.id -> beamVehicle),
          new Coord(0.0, 0.0),
          Vector(),
          Set.empty,
          new RouteHistory(beamConfig),
          VehiclesAdjustment.getVehicleAdjustment(beamScenario),
          configHolder
        )
      )
      scheduler ! ScheduleTrigger(InitializeTrigger(0), householdActor)
      scheduler ! StartSchedule(0)

//      expectMsgType[TourModeChoiceEvent]

      val routingRequest = expectMsgType[RoutingRequest]
      lastSender ! RoutingResponse(
        itineraries = Vector(
          EmbodiedBeamTrip(
            legs = Vector(
              EmbodiedBeamLeg(
                beamLeg = BeamLeg(
                  startTime = 28800,
                  mode = BeamMode.WALK,
                  duration = 50,
                  travelPath = BeamPath(
                    linkIds = Array(1, 2),
                    linkTravelTime = Array(50, 50),
                    transitStops = None,
                    startPoint = SpaceTime(0.0, 0.0, 28800),
                    endPoint = SpaceTime(0.01, 0.0, 28850),
                    distanceInM = 1000d
                  )
                ),
                beamVehicleId = Id.createVehicleId("body-dummyAgent"),
                Id.create("TRANSIT-TYPE-DEFAULT", classOf[BeamVehicleType]),
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
                    linkIds = Array(3, 4),
                    linkTravelTime = Array(50, 50),
                    transitStops = None,
                    startPoint = SpaceTime(0.01, 0.0, 28950),
                    endPoint = SpaceTime(0.01, 0.01, 29000),
                    distanceInM = 1000d
                  )
                ),
                beamVehicleId = Id.createVehicleId("car-1"),
                Id.create("TRANSIT-TYPE-DEFAULT", classOf[BeamVehicleType]),
                asDriver = true,
                cost = 0.0,
                unbecomeDriverOnCompletion = true
              )
            )
          )
        ),
        requestId = routingRequest.requestId,
        request = None,
        isEmbodyWithCurrentTravelTime = false,
        triggerId = routingRequest.triggerId
      )

//      expectMsgType[TourModeChoiceEvent]
      expectMsgType[ModeChoiceEvent]
      expectMsgType[ActivityEndEvent]
      expectMsgType[BeamPersonDepartureEvent]

      expectMsgType[PersonEntersVehicleEvent]
      expectMsgType[VehicleEntersTrafficEvent]
      expectMsgType[VehicleLeavesTrafficEvent]
      expectMsgType[PathTraversalEvent]

      expectMsgType[PersonEntersVehicleEvent]
      expectMsgType[LeavingParkingEvent]
      expectMsgType[VehicleEntersTrafficEvent]
      expectMsgType[VehicleLeavesTrafficEvent]
      expectMsgType[PathTraversalEvent]
      expectMsgType[VehicleEntersTrafficEvent]
      expectMsgType[LinkLeaveEvent]
      expectMsgType[LinkEnterEvent]
      expectMsgType[VehicleLeavesTrafficEvent]
      expectMsgType[PathTraversalEvent]
      expectMsgType[ParkingEvent]
      expectMsgType[PersonLeavesVehicleEvent]

      expectMsgType[VehicleEntersTrafficEvent]
      expectMsgType[VehicleLeavesTrafficEvent]
      expectMsgType[PathTraversalEvent]

      expectMsgType[PersonLeavesVehicleEvent]
      expectMsgType[TeleportationArrivalEvent]

      expectMsgType[PersonArrivalEvent]
      expectMsgType[ActivityStartEvent]

      expectMsgType[CompletionNotice]
    }

  }

  private def createTestPerson(
    personId: Id[Person],
    vehicleId: Id[Vehicle],
    mode: BeamMode,
    withRoute: Boolean = true
  ) = {
    val person = PopulationUtils.getFactory.createPerson(personId)
    putDefaultBeamAttributes(person, Vector(mode))
    val plan = PopulationUtils.getFactory.createPlan()
    val homeActivity = PopulationUtils.createActivityFromLinkId("home", Id.createLinkId(1))
    homeActivity.setEndTime(28800) // 8:00:00 AM
    homeActivity.setCoord(homeLocation)
    plan.addActivity(homeActivity)
    val leg = PopulationUtils.createLeg(mode.matsimMode)
    if (withRoute) {
      val route = RouteUtils.createLinkNetworkRouteImpl(
        Id.createLinkId(228),
        Array(206, 180, 178, 184, 102).map(Id.createLinkId(_)),
        Id.createLinkId(108)
      )
      route.setVehicleId(vehicleId)
      leg.setRoute(route)
    }
    plan.addLeg(leg)
    val workActivity = PopulationUtils.createActivityFromLinkId("work", Id.createLinkId(2))
    workActivity.setEndTime(61200) //5:00:00 PM
    workActivity.setCoord(workLocation)
    plan.addActivity(workActivity)
    person.addPlan(plan)
    person
  }

  override def afterAll(): Unit = {
    super.afterAll()
  }

  after {
    import scala.concurrent.duration._
    import scala.language.postfixOps
    //we need to prevent getting this CompletionNotice from the Scheduler in the next test
    receiveWhile(1500 millis) { case _: CompletionNotice =>
    }
  }

}
