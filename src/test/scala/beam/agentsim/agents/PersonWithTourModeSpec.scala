package beam.agentsim.agents

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.{ask, pipe}
import akka.testkit.{ImplicitSender, TestActorRef, TestKitBase, TestProbe}
import akka.util.Timeout

import scala.concurrent.duration._
import beam.agentsim.agents.PersonTestUtil._
import beam.agentsim.agents.choice.logit.TourModeChoiceModel
import beam.agentsim.agents.choice.mode.ModeChoiceUniformRandom
import beam.agentsim.agents.household.HouseholdActor.{
  HouseholdActor,
  MobilityStatusInquiry,
  MobilityStatusResponse,
  ReleaseVehicle
}
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles._
import beam.agentsim.events._
import beam.agentsim.infrastructure._
import beam.agentsim.scheduler.{BeamAgentScheduler, HasTriggerId}
import beam.agentsim.scheduler.BeamAgentScheduler.{
  CompletionNotice,
  ScheduleKillTrigger,
  ScheduleTrigger,
  SchedulerMessage,
  SchedulerProps,
  StartSchedule
}
import beam.router.BeamRouter._
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{BIKE, CAR, WALK}
import beam.agentsim.agents.modalbehaviors.DrivesVehicle.{ActualVehicle, Token}
import beam.agentsim.agents.modalbehaviors.ChoosesMode
import beam.router.RouteHistory
import beam.router.TourModes.BeamTourMode
import beam.router.TourModes.BeamTourMode.{CAR_BASED, WALK_BASED}
import beam.router.model._
import beam.router.skim.core.AbstractSkimmerEvent
import beam.sim.population.{AttributesOfIndividual, HouseholdAttributes}
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
import org.matsim.api.core.v01.network.Link
import org.matsim.core.population.routes.{GenericRouteImpl, NetworkRoute, RouteUtils}
import org.matsim.households.{Household, HouseholdsFactoryImpl}
import org.matsim.vehicles._
import org.scalatest.funspec.AnyFunSpecLike
import org.scalatest.matchers.should.Matchers._
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import scala.collection.{mutable, JavaConverters}
import scala.concurrent.ExecutionContext
import scala.language.postfixOps
import scala.util.control.NonFatal

class PersonWithTourModeSpec
    extends AnyFunSpecLike
    with TestKitBase
    with SimRunnerForTest
    with BeforeAndAfterAll
    with BeforeAndAfter
    with ImplicitSender
    with BeamvilleFixtures {

  private implicit val timeout: Timeout = Timeout(60, TimeUnit.SECONDS)
  private implicit val executionContext: ExecutionContext = system.dispatcher

  lazy val config: Config = ConfigFactory
    .parseString(
      """
        akka.log-dead-letters = 10
        akka.actor.debug.fsm = true
        akka.loglevel = debug
        akka.test.timefactor = 6
        """
    )
    .withFallback(testConfig("test/input/beamville/beam.conf"))
    .resolve()

  lazy implicit val system: ActorSystem = ActorSystem("PersonWithTourModeSpec", config)

  override def outputDirPath: String = TestConfigUtils.testOutputDir

  private val householdsFactory: HouseholdsFactoryImpl = new HouseholdsFactoryImpl()
  private val hoseHoldDummyId = Id.create("dummy", classOf[Household])

  private lazy val modeChoiceCalculator = new ModeChoiceUniformRandom(beamConfig)
  private lazy val tourModeChoiceCalculator = new TourModeChoiceModel(beamConfig)

  val homeLocation = new Coord(170308.4, 2964.6474)
  val workLocation = new Coord(169346.4, 876.7536)
  val otherLocation = new Coord(168346.4, 1276.7536)

  describe("A PersonAgent") {

    it("should know how to take a car trip on a car_based tour when the mode is already in its plan") {
      val eventsManager = new EventsManagerImpl()
      eventsManager.addHandler(
        new BasicEventHandler {
          override def handleEvent(event: Event): Unit = {
            event match {
              case _: ModeChoiceEvent | _: TourModeChoiceEvent | _: ActivityEndEvent | _: ActivityStartEvent |
                  _: ReplanningEvent =>
                self ! event
              case _ =>
            }
          }
        }
      )
      val vehicleId = Id.createVehicleId("dummySharedCar")
      val vehicleType = beamScenario.vehicleTypes(Id.create("beamVilleCar", classOf[BeamVehicleType]))
      val beamVehicle = new BeamVehicle(vehicleId, new Powertrain(0.0), vehicleType)

      val household = householdsFactory.createHousehold(hoseHoldDummyId)
      val population = PopulationUtils.createPopulation(ConfigUtils.createConfig())

      val person: Person =
        createTestPerson(Id.createPersonId("dummyAgent"), vehicleId, Some(CAR), Some(CAR_BASED), Some(beamVehicle.id))
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

      expectMsgType[ActivityStartEvent]

      lastSender ! ScheduleKillTrigger(lastSender, walkFromParkingRoutingRequest.triggerId)
      receiveWhile(500 millis) {
        case _: SchedulerMessage =>
        case x: Event            => println(x.toString)
        case x: HasTriggerId     => println(x.toString)
      }
    }
    it("should choose a car_based tour when a car trip is already in its plan") {
      val eventsManager = new EventsManagerImpl()
      eventsManager.addHandler(
        new BasicEventHandler {
          override def handleEvent(event: Event): Unit = {
            event match {
              case _: ModeChoiceEvent | _: TourModeChoiceEvent | _: ActivityEndEvent | _: ActivityStartEvent |
                  _: ReplanningEvent =>
                self ! event
              case _ =>
            }
          }
        }
      )
      val vehicleId = Id.createVehicleId("car-dummyAgent")
      val vehicleType = beamScenario.vehicleTypes(Id.create("beamVilleCar", classOf[BeamVehicleType]))
      val beamVehicle = new BeamVehicle(vehicleId, new Powertrain(0.0), vehicleType)

      val household = householdsFactory.createHousehold(hoseHoldDummyId)
      val population = PopulationUtils.createPopulation(ConfigUtils.createConfig())

      val person: Person =
        createTestPerson(Id.createPersonId("dummyAgent"), vehicleId, Some(CAR), None, None)
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

      // The agent will ask for current travel times for a route it already knows.
      val tmc = expectMsgType[TourModeChoiceEvent]
      // Make sure that they chose a car_based tour
      assert(tmc.tourMode === "car_based")
      // Make sure it didn't actually go through the process of calculating utilities b/c it didn't have to
      assert(tmc.tourModeToUtilityString === "")

      expectMsgType[ModeChoiceEvent]
      expectMsgType[ActivityEndEvent]

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

      expectMsgType[ActivityStartEvent]
      lastSender ! ScheduleKillTrigger(lastSender, walkFromParkingRoutingRequest.triggerId)

      receiveWhile(500 millis) {
        case _: SchedulerMessage =>
        case x: Event            => println(x.toString)
        case x: HasTriggerId     => println(x.toString)
      }
    }
    it("should choose a car trip when a car_based tour is already in its plan") {
      val eventsManager = new EventsManagerImpl()
      eventsManager.addHandler(
        new BasicEventHandler {
          override def handleEvent(event: Event): Unit = {
            event match {
              case _: ModeChoiceEvent | _: TourModeChoiceEvent | _: ActivityEndEvent | _: ActivityStartEvent |
                  _: ReplanningEvent =>
                self ! event
              case _ =>
            }
          }
        }
      )
      val vehicleId = Id.createVehicleId("car-dummyAgent")
      val vehicleType = beamScenario.vehicleTypes(Id.create("beamVilleCar", classOf[BeamVehicleType]))
      val beamVehicle = new BeamVehicle(vehicleId, new Powertrain(0.0), vehicleType)

      val household = householdsFactory.createHousehold(hoseHoldDummyId)
      val population = PopulationUtils.createPopulation(ConfigUtils.createConfig())

      val person: Person =
        createTestPerson(Id.createPersonId("dummyAgent"), vehicleId, None, Some(CAR_BASED), None, withRoute = false)
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

      val routingRequest = expectMsgType[RoutingRequest]
      assert(routingRequest.withTransit === false)
      val personVehicle = routingRequest.streetVehicles.find(_.mode == WALK).get
      val linkIds = Array[Int](228, 206, 180, 178, 184, 102)
      lastSender ! RoutingResponse(
        Vector(
          EmbodiedBeamTrip(
            legs = Vector(
              EmbodiedBeamLeg.dummyLegAt(
                routingRequest.departureTime,
                routingRequest.streetVehicles.find(_.mode == WALK).get.id,
                false,
                services.geo.utm2Wgs(routingRequest.originUTM),
                WALK,
                personVehicle.vehicleTypeId
              ),
              createEmbodiedBeamLeg(routingRequest, beamVehicle.toStreetVehicle, linkIds, 50d),
              EmbodiedBeamLeg.dummyLegAt(
                routingRequest.departureTime + 250,
                routingRequest.streetVehicles.find(_.mode == WALK).get.id,
                true,
                services.geo.utm2Wgs(routingRequest.destinationUTM),
                WALK,
                personVehicle.vehicleTypeId
              )
            )
          ),
          EmbodiedBeamTrip(legs = Vector(createEmbodiedBeamLeg(routingRequest, personVehicle, linkIds, 150d)))
        ),
        requestId = 1,
        request = None,
        isEmbodyWithCurrentTravelTime = false,
        triggerId = routingRequest.triggerId
      )

      val mce = expectMsgType[ModeChoiceEvent]
      assert(mce.mode === "car")
      assert(mce.currentTourMode === "car_based")
      assert(mce.availableAlternatives === "CAR")
      expectMsgType[ActivityEndEvent]

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

      expectMsgType[ActivityStartEvent]

      lastSender ! ScheduleKillTrigger(lastSender, routingRequest.triggerId)

      receiveWhile(500 millis) {
        case _: SchedulerMessage =>
        case x: Event            => println(x.toString)
        case x: HasTriggerId     => println(x.toString)
      }
    }
    it("should choose a walk trip when a walk_based tour is already in its plan") {
      val eventsManager = new EventsManagerImpl()
      eventsManager.addHandler(
        new BasicEventHandler {
          override def handleEvent(event: Event): Unit = {
            event match {
              case _: ModeChoiceEvent | _: TourModeChoiceEvent | _: ActivityEndEvent | _: ActivityStartEvent |
                  _: ReplanningEvent =>
                self ! event
              case _ =>
            }
          }
        }
      )
      val vehicleId = Id.createVehicleId("car-dummyAgent")
      val vehicleType = beamScenario.vehicleTypes(Id.create("beamVilleCar", classOf[BeamVehicleType]))
      val beamVehicle = new BeamVehicle(vehicleId, new Powertrain(0.0), vehicleType)

      val household = householdsFactory.createHousehold(hoseHoldDummyId)
      val population = PopulationUtils.createPopulation(ConfigUtils.createConfig())

      val person: Person =
        createTestPerson(Id.createPersonId("dummyAgent"), vehicleId, None, Some(WALK_BASED), None, withRoute = false)
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

      val routingRequest = expectMsgType[RoutingRequest]
      assert(routingRequest.withTransit === true)
      val personVehicle = routingRequest.streetVehicles.find(_.mode == WALK).get
      val linkIds = Array[Int](228, 206, 180, 178, 184, 102)
      lastSender ! RoutingResponse(
        Vector(
          EmbodiedBeamTrip(
            legs = Vector(
              EmbodiedBeamLeg.dummyLegAt(
                routingRequest.departureTime,
                routingRequest.streetVehicles.find(_.mode == WALK).get.id,
                false,
                services.geo.utm2Wgs(routingRequest.originUTM),
                WALK,
                personVehicle.vehicleTypeId
              ),
              createEmbodiedBeamLeg(routingRequest, beamVehicle.toStreetVehicle, linkIds, 50d),
              EmbodiedBeamLeg.dummyLegAt(
                routingRequest.departureTime + 250,
                routingRequest.streetVehicles.find(_.mode == WALK).get.id,
                true,
                services.geo.utm2Wgs(routingRequest.destinationUTM),
                WALK,
                personVehicle.vehicleTypeId
              )
            )
          ),
          EmbodiedBeamTrip(legs = Vector(createEmbodiedBeamLeg(routingRequest, personVehicle, linkIds, 150d)))
        ),
        requestId = 1,
        request = None,
        isEmbodyWithCurrentTravelTime = false,
        triggerId = routingRequest.triggerId
      )
      val mce = expectMsgType[ModeChoiceEvent]
      assert(mce.mode === "walk")
      assert(mce.currentTourMode === "walk_based")
      assert(mce.availableAlternatives === "WALK")
      expectMsgType[ActivityEndEvent]

      lastSender ! ScheduleKillTrigger(lastSender, routingRequest.triggerId)
      receiveWhile(500 millis) {
        case _: SchedulerMessage =>
        case x: Event            => println(x.toString)
        case x: HasTriggerId     => println(x.toString)
      }
    }
    it("should choose between a walk and car trip when tour mode is not set in plan") {
      val eventsManager = new EventsManagerImpl()
      eventsManager.addHandler(
        new BasicEventHandler {
          override def handleEvent(event: Event): Unit = {
            event match {
              case _: ModeChoiceEvent | _: TourModeChoiceEvent | _: ActivityEndEvent | _: ActivityStartEvent |
                  _: ReplanningEvent =>
                self ! event
              case _ =>
            }
          }
        }
      )
      val vehicleId = Id.createVehicleId("car-dummyAgent")
      val vehicleType = beamScenario.vehicleTypes(Id.create("beamVilleCar", classOf[BeamVehicleType]))
      val beamVehicle = new BeamVehicle(vehicleId, new Powertrain(0.0), vehicleType)

      val household = householdsFactory.createHousehold(hoseHoldDummyId)
      val population = PopulationUtils.createPopulation(ConfigUtils.createConfig())

      val person: Person =
        createTestPerson(Id.createPersonId("dummyAgent"), vehicleId, None, None, None, withRoute = false)
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

      val routingRequest = expectMsgType[RoutingRequest]
      val personVehicle = routingRequest.streetVehicles.find(_.mode == WALK).get
      val linkIds = Array[Int](228, 206, 180, 178, 184, 102)
      lastSender ! RoutingResponse(
        Vector(
          EmbodiedBeamTrip(
            legs = Vector(
              EmbodiedBeamLeg.dummyLegAt(
                routingRequest.departureTime,
                routingRequest.streetVehicles.find(_.mode == WALK).get.id,
                false,
                services.geo.utm2Wgs(routingRequest.originUTM),
                WALK,
                personVehicle.vehicleTypeId
              ),
              createEmbodiedBeamLeg(routingRequest, beamVehicle.toStreetVehicle, linkIds, 50d),
              EmbodiedBeamLeg.dummyLegAt(
                routingRequest.departureTime + 250,
                routingRequest.streetVehicles.find(_.mode == WALK).get.id,
                true,
                services.geo.utm2Wgs(routingRequest.destinationUTM),
                WALK,
                personVehicle.vehicleTypeId
              )
            )
          ),
          EmbodiedBeamTrip(legs = Vector(createEmbodiedBeamLeg(routingRequest, personVehicle, linkIds, 150d)))
        ),
        requestId = 1,
        request = None,
        isEmbodyWithCurrentTravelTime = false,
        triggerId = routingRequest.triggerId
      )

      val tmc = expectMsgType[TourModeChoiceEvent]
      val modeUtilities = tmc.tourModeToUtilityString
        .replace(" ", "")
        .split("->")
        .flatMap(_.split(";"))
        .sliding(2, 2)
        .map { x => x(0) -> x(1).toDouble }
        .toMap

      val chosenTourMode = tmc.tourMode
      assert(modeUtilities("CAR_BASED") > Double.NegativeInfinity)
      assert(modeUtilities("WALK_BASED") > Double.NegativeInfinity)
      assert(modeUtilities("BIKE_BASED") === Double.NegativeInfinity)

      val mce = expectMsgType[ModeChoiceEvent]
      assert(mce.currentTourMode === chosenTourMode)
      expectMsgType[ActivityEndEvent]

      lastSender ! ScheduleKillTrigger(lastSender, routingRequest.triggerId)

      receiveWhile(500 millis) {
        case _: SchedulerMessage =>
        case x: Event            => println(x.toString)
        case x: HasTriggerId     => println(x.toString)
      }
    }
    it("should only consider walk_based tours if given only a shared car") {
      val eventsManager = new EventsManagerImpl()
      eventsManager.addHandler(
        new BasicEventHandler {
          override def handleEvent(event: Event): Unit = {
            event match {
              case _: ModeChoiceEvent | _: TourModeChoiceEvent | _: ActivityEndEvent | _: ActivityStartEvent |
                  _: ReplanningEvent =>
                self ! event
              case _ =>
            }
          }
        }
      )
      val personalVehicleId = Id.createVehicleId("car-dummyAgent")
//      val personalVehicleType = beamScenario.vehicleTypes(Id.create("beamVilleCar", classOf[BeamVehicleType]))
//      val beamVehicle = new BeamVehicle(personalVehicleId, new Powertrain(0.0), personalVehicleType)

      val household = householdsFactory.createHousehold(hoseHoldDummyId)
      val population = PopulationUtils.createPopulation(ConfigUtils.createConfig())

      val person: Person =
        createTestPerson(Id.createPersonId("dummyAgent"), personalVehicleId, None, None, None, withRoute = false)
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
      val mockSharedVehicleFleet = TestProbe()

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
            Map(),
            new Coord(0.0, 0.0),
            sharedVehicleFleets = Vector(mockSharedVehicleFleet.ref),
            Set(beamScenario.vehicleTypes(Id.create("sharedVehicle-sharedCar", classOf[BeamVehicleType]))),
            new RouteHistory(beamConfig),
            VehiclesAdjustment.getVehicleAdjustment(beamScenario),
            configHolder
          )
        )
      )
      scheduler ! ScheduleTrigger(InitializeTrigger(0), householdActor)

      scheduler ! StartSchedule(0)

      val inq = mockSharedVehicleFleet.expectMsgType[MobilityStatusInquiry]

      val vehicleType = beamScenario.vehicleTypes(Id.create("sharedVehicle-sharedCar", classOf[BeamVehicleType]))
      val managerId =
        VehicleManager.createOrGetReservedFor("shared-fleet-1", Some(VehicleManager.TypeEnum.Shared)).managerId
      // I give it a car to use.
      val vehicle = new BeamVehicle(
        Id.create("sharedVehicle-sharedCar", classOf[BeamVehicle]),
        new Powertrain(0.0),
        vehicleType,
        vehicleManagerId = new AtomicReference(managerId)
      )
      vehicle.setManager(Some(mockSharedVehicleFleet.ref))

      (parkingManager ? ParkingInquiry.init(
        SpaceTime(0.0, 0.0, 28800),
        "wherever",
        triggerId = 0
      )).collect { case ParkingInquiryResponse(stall, _, triggerId) =>
        vehicle.useParkingStall(stall)
        MobilityStatusResponse(Vector(ActualVehicle(vehicle)), triggerId)
      } pipeTo mockSharedVehicleFleet.lastSender

      val routingRequest = expectMsgType[RoutingRequest]
      val personVehicle = routingRequest.streetVehicles.find(_.mode == WALK).get
      val linkIds = Array[Int](228, 206, 180, 178, 184, 102)
      lastSender ! RoutingResponse(
        Vector(
          EmbodiedBeamTrip(
            legs = Vector(
              EmbodiedBeamLeg.dummyLegAt(
                routingRequest.departureTime,
                routingRequest.streetVehicles.find(_.mode == WALK).get.id,
                false,
                services.geo.utm2Wgs(routingRequest.originUTM),
                WALK,
                personVehicle.vehicleTypeId
              ),
              createEmbodiedBeamLeg(routingRequest, vehicle.toStreetVehicle, linkIds, 50d),
              EmbodiedBeamLeg.dummyLegAt(
                routingRequest.departureTime + 250,
                routingRequest.streetVehicles.find(_.mode == WALK).get.id,
                true,
                services.geo.utm2Wgs(routingRequest.destinationUTM),
                WALK,
                personVehicle.vehicleTypeId
              )
            )
          ),
          EmbodiedBeamTrip(legs = Vector(createEmbodiedBeamLeg(routingRequest, personVehicle, linkIds, 150d)))
        ),
        requestId = 1,
        request = None,
        isEmbodyWithCurrentTravelTime = false,
        triggerId = routingRequest.triggerId
      )

      val tmc = expectMsgType[TourModeChoiceEvent]
      val modeUtilities = tmc.tourModeToUtilityString
        .replace(" ", "")
        .split("->")
        .flatMap(_.split(";"))
        .sliding(2, 2)
        .map { x => x(0) -> x(1).toDouble }
        .toMap

      val chosenTourMode = tmc.tourMode
      assert(modeUtilities("CAR_BASED") === Double.NegativeInfinity)
      assert(modeUtilities("WALK_BASED") > Double.NegativeInfinity)
      assert(modeUtilities("BIKE_BASED") === Double.NegativeInfinity)

      val mce = expectMsgType[ModeChoiceEvent]
      assert(mce.currentTourMode === "walk_based")
      // Make sure that they consider using the shared car, even though they are on a walk_based tour (they can do this
      // because they don't need to bring the car home, so they can take any walk_based mode for the rest of the tour)
      assert(mce.availableAlternatives contains "CAR")
      assert(mce.availableAlternatives contains "WALK")
      expectMsgType[ActivityEndEvent]

      lastSender ! ScheduleKillTrigger(lastSender, routingRequest.triggerId)

      receiveWhile(500 millis) {
        case _: SchedulerMessage =>
        case x: Event            => println(x.toString)
        case x: HasTriggerId     => println(x.toString)
      }
    }

    it("should be able to handle a plan with nested tours") {
      val eventsManager = new EventsManagerImpl()
      eventsManager.addHandler(
        new BasicEventHandler {
          override def handleEvent(event: Event): Unit = {
            event match {
              case _: ModeChoiceEvent | _: TourModeChoiceEvent | _: ActivityEndEvent | _: ActivityStartEvent |
                  _: ReplanningEvent =>
                self ! event
              case _ =>
            }
          }
        }
      )
      val vehicleId = Id.createVehicleId("car-dummyAgent")
      val vehicleType = beamScenario.vehicleTypes(Id.create("beamVilleCar", classOf[BeamVehicleType]))
      val beamVehicle = new BeamVehicle(vehicleId, new Powertrain(0.0), vehicleType)

      val household = householdsFactory.createHousehold(hoseHoldDummyId)
      val population = PopulationUtils.createPopulation(ConfigUtils.createConfig())

      val person: Person =
        createTestPersonWithSubtour(Id.createPersonId("dummyAgent"), Some(CAR_BASED), None, None, None, None, None)
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

      val routingRequest = expectMsgType[RoutingRequest]
      assert(routingRequest.withTransit === false)
      val personVehicle = routingRequest.streetVehicles.find(_.mode == WALK).get
      val linkIds = Array[Int](228, 206, 180, 178, 184, 102)
      lastSender ! RoutingResponse(
        Vector(
          EmbodiedBeamTrip(
            legs = Vector(
              EmbodiedBeamLeg.dummyLegAt(
                routingRequest.departureTime,
                routingRequest.streetVehicles.find(_.mode == WALK).get.id,
                false,
                services.geo.utm2Wgs(routingRequest.originUTM),
                WALK,
                personVehicle.vehicleTypeId
              ),
              createEmbodiedBeamLeg(routingRequest, beamVehicle.toStreetVehicle, linkIds, 50d),
              EmbodiedBeamLeg.dummyLegAt(
                routingRequest.departureTime + 250,
                routingRequest.streetVehicles.find(_.mode == WALK).get.id,
                true,
                services.geo.utm2Wgs(routingRequest.destinationUTM),
                WALK,
                personVehicle.vehicleTypeId
              )
            )
          ),
          EmbodiedBeamTrip(legs = Vector(createEmbodiedBeamLeg(routingRequest, personVehicle, linkIds, 150d)))
        ),
        requestId = 1,
        request = None,
        isEmbodyWithCurrentTravelTime = false,
        triggerId = routingRequest.triggerId
      )

      val mce = expectMsgType[ModeChoiceEvent]
      assert(mce.mode === "car")
      assert(mce.currentTourMode === "car_based")
      assert(mce.availableAlternatives === "CAR")
      expectMsgType[ActivityEndEvent]

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

      expectMsgType[ActivityStartEvent]
      val routingRequest2 = expectMsgType[RoutingRequest]

      lastSender ! RoutingResponse(
        itineraries = Vector(
          EmbodiedBeamTrip(
            legs = Vector(
              EmbodiedBeamLeg.dummyLegAt(
                routingRequest2.departureTime,
                routingRequest2.streetVehicles.find(_.mode == WALK).get.id,
                false,
                services.geo.utm2Wgs(routingRequest2.originUTM),
                WALK,
                personVehicle.vehicleTypeId
              ),
              createEmbodiedBeamLeg(routingRequest2, beamVehicle.toStreetVehicle, linkIds, 50d),
              EmbodiedBeamLeg.dummyLegAt(
                routingRequest2.departureTime + 250,
                routingRequest2.streetVehicles.find(_.mode == WALK).get.id,
                true,
                services.geo.utm2Wgs(routingRequest2.destinationUTM),
                WALK,
                personVehicle.vehicleTypeId
              )
            )
          ),
          EmbodiedBeamTrip(legs = Vector(createEmbodiedBeamLeg(routingRequest2, personVehicle, linkIds, 150d)))
        ),
        requestId = routingRequest2.requestId,
        request = Some(routingRequest2),
        isEmbodyWithCurrentTravelTime = false,
        triggerId = routingRequest2.triggerId
      )

      val tmc = expectMsgType[TourModeChoiceEvent]
      val modeUtilities = tmc.tourModeToUtilityString
        .replace(" ", "")
        .split("->")
        .flatMap(_.split(";"))
        .sliding(2, 2)
        .map { x => x(0) -> x(1).toDouble }
        .toMap

      assert(modeUtilities("CAR_BASED") > Double.NegativeInfinity)
      assert(modeUtilities("WALK_BASED") > Double.NegativeInfinity)
      assert(modeUtilities("BIKE_BASED") === Double.NegativeInfinity)
      assert(tmc.availablePersonalStreetVehiclesString.contains("beamVilleCar"))
      lastSender ! ScheduleKillTrigger(lastSender, routingRequest.triggerId)

      receiveWhile(500 millis) {
        case _: SchedulerMessage =>
        case x: Event            => println(x.toString)
        case x: HasTriggerId     => println(x.toString)
      }
    }
    it("should be able to their personal car again after completing a walk_based subtour") {
      val eventsManager = new EventsManagerImpl()
      eventsManager.addHandler(
        new BasicEventHandler {
          override def handleEvent(event: Event): Unit = {
            event match {
              case _: ModeChoiceEvent | _: TourModeChoiceEvent | _: ActivityEndEvent | _: ActivityStartEvent |
                  _: ReplanningEvent =>
                self ! event
              case _ =>
            }
          }
        }
      )
      val vehicleId = Id.createVehicleId("car-dummyAgent")
      val vehicleType = beamScenario.vehicleTypes(Id.create("beamVilleCar", classOf[BeamVehicleType]))
      val beamVehicle = new BeamVehicle(vehicleId, new Powertrain(0.0), vehicleType)

      val household = householdsFactory.createHousehold(hoseHoldDummyId)
      val population = PopulationUtils.createPopulation(ConfigUtils.createConfig())

      val person: Person =
        createTestPersonWithSubtour(
          Id.createPersonId("dummyAgent"),
          Some(CAR_BASED),
          None,
          None,
          Some(WALK_BASED),
          None,
          None
        )
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

      val routingRequestForWorkTrip = expectMsgType[RoutingRequest]
      assert(routingRequestForWorkTrip.withTransit === false)
      val personVehicle = routingRequestForWorkTrip.streetVehicles.find(_.mode == WALK).get
      val linkIds = Array[Int](228, 206, 180, 178, 184, 102)
      lastSender ! RoutingResponse(
        Vector(
          EmbodiedBeamTrip(
            legs = Vector(
              EmbodiedBeamLeg.dummyLegAt(
                routingRequestForWorkTrip.departureTime,
                routingRequestForWorkTrip.streetVehicles.find(_.mode == WALK).get.id,
                false,
                services.geo.utm2Wgs(routingRequestForWorkTrip.originUTM),
                WALK,
                personVehicle.vehicleTypeId
              ),
              createEmbodiedBeamLeg(routingRequestForWorkTrip, beamVehicle.toStreetVehicle, linkIds, 50d),
              EmbodiedBeamLeg.dummyLegAt(
                routingRequestForWorkTrip.departureTime + 250,
                routingRequestForWorkTrip.streetVehicles.find(_.mode == WALK).get.id,
                true,
                services.geo.utm2Wgs(routingRequestForWorkTrip.destinationUTM),
                WALK,
                personVehicle.vehicleTypeId
              )
            )
          ),
          EmbodiedBeamTrip(legs =
            Vector(createEmbodiedBeamLeg(routingRequestForWorkTrip, personVehicle, linkIds, 150d))
          )
        ),
        requestId = 1,
        request = None,
        isEmbodyWithCurrentTravelTime = false,
        triggerId = routingRequestForWorkTrip.triggerId
      )

      val modeChoiceForWorkTrip = expectMsgType[ModeChoiceEvent]
      assert(modeChoiceForWorkTrip.mode === "car")
      assert(modeChoiceForWorkTrip.currentTourMode === "car_based")
      assert(modeChoiceForWorkTrip.availableAlternatives === "CAR")

      expectMsgType[ActivityEndEvent]
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
                    linkIds = Array(80, 101),
                    linkTravelTime = Array(50, 50),
                    transitStops = None,
                    startPoint =
                      SpaceTime(services.geo.utm2Wgs(parkingLocation), walkFromParkingRoutingRequest.departureTime),
                    endPoint = SpaceTime(
                      services.geo.utm2Wgs(walkFromParkingRoutingRequest.destinationUTM),
                      walkFromParkingRoutingRequest.departureTime + 50
                    ),
                    distanceInM = 100d
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

      expectMsgType[ActivityStartEvent]
      val routingRequestForLunchTrip = expectMsgType[RoutingRequest]

      lastSender ! RoutingResponse(
        itineraries = Vector(
          EmbodiedBeamTrip(
            legs = Vector(
              EmbodiedBeamLeg(
                beamLeg = BeamLeg(
                  startTime = routingRequestForLunchTrip.departureTime,
                  mode = BeamMode.WALK,
                  duration = 50,
                  travelPath = BeamPath(
                    linkIds = Array(101, 100),
                    linkTravelTime = Array(50, 50),
                    transitStops = None,
                    startPoint = SpaceTime(
                      services.geo.utm2Wgs(routingRequestForLunchTrip.originUTM),
                      routingRequestForLunchTrip.departureTime
                    ),
                    endPoint = SpaceTime(
                      services.geo.utm2Wgs(routingRequestForLunchTrip.destinationUTM),
                      routingRequestForLunchTrip.departureTime + 50
                    ),
                    distanceInM = 100d
                  )
                ),
                beamVehicleId = routingRequestForLunchTrip.streetVehicles.find(_.mode == WALK).get.id,
                routingRequestForLunchTrip.streetVehicles.find(_.mode == WALK).get.vehicleTypeId,
                asDriver = true,
                cost = 0.0,
                unbecomeDriverOnCompletion = true
              )
            )
          )
        ),
        requestId = routingRequestForLunchTrip.requestId,
        request = None,
        isEmbodyWithCurrentTravelTime = false,
        triggerId = routingRequestForLunchTrip.triggerId
      )

      val modeChoiceEventForLunchTrip = expectMsgType[ModeChoiceEvent]
      assert(modeChoiceEventForLunchTrip.mode === "walk")
      assert(modeChoiceEventForLunchTrip.currentTourMode === "walk_based")
      assert(modeChoiceEventForLunchTrip.availableAlternatives === "WALK")
      expectMsgType[ActivityEndEvent]

      expectMsgType[ActivityStartEvent]

      val routingRequestForReturnToWork = expectMsgType[RoutingRequest]

      lastSender ! RoutingResponse(
        itineraries = Vector(
          EmbodiedBeamTrip(
            legs = Vector(
              EmbodiedBeamLeg(
                beamLeg = BeamLeg(
                  startTime = routingRequestForReturnToWork.departureTime,
                  mode = BeamMode.WALK,
                  duration = 50,
                  travelPath = BeamPath(
                    linkIds = Array(100, 101),
                    linkTravelTime = Array(50, 50),
                    transitStops = None,
                    startPoint = SpaceTime(
                      services.geo.utm2Wgs(routingRequestForReturnToWork.originUTM),
                      routingRequestForReturnToWork.departureTime
                    ),
                    endPoint = SpaceTime(
                      services.geo.utm2Wgs(routingRequestForReturnToWork.destinationUTM),
                      routingRequestForReturnToWork.departureTime + 50
                    ),
                    distanceInM = 100d
                  )
                ),
                beamVehicleId = routingRequestForReturnToWork.streetVehicles.find(_.mode == WALK).get.id,
                routingRequestForReturnToWork.streetVehicles.find(_.mode == WALK).get.vehicleTypeId,
                asDriver = true,
                cost = 0.0,
                unbecomeDriverOnCompletion = true
              )
            )
          )
        ),
        requestId = routingRequestForLunchTrip.requestId,
        request = None,
        isEmbodyWithCurrentTravelTime = false,
        triggerId = routingRequestForLunchTrip.triggerId
      )
      expectMsgType[ModeChoiceEvent]
      expectMsgType[ActivityEndEvent]
      expectMsgType[ActivityStartEvent]
      val routingRequestForReturnToHome = expectMsgType[RoutingRequest]
      assert(routingRequestForWorkTrip.withTransit === false)
      lastSender ! RoutingResponse(
        Vector(
          EmbodiedBeamTrip(
            legs = Vector(
              EmbodiedBeamLeg(
                beamLeg = BeamLeg(
                  startTime = routingRequestForReturnToHome.departureTime,
                  mode = BeamMode.WALK,
                  duration = 50,
                  travelPath = BeamPath(
                    linkIds = Array(101, 80),
                    linkTravelTime = Array(50, 50),
                    transitStops = None,
                    startPoint = SpaceTime(
                      services.geo.utm2Wgs(routingRequestForReturnToHome.originUTM),
                      routingRequestForReturnToHome.departureTime
                    ),
                    endPoint = SpaceTime(
                      services.geo.utm2Wgs(parkingLocation),
                      routingRequestForReturnToHome.departureTime + 50
                    ),
                    distanceInM = 100d
                  )
                ),
                beamVehicleId = routingRequestForReturnToHome.streetVehicles.find(_.mode == WALK).get.id,
                routingRequestForReturnToHome.streetVehicles.find(_.mode == WALK).get.vehicleTypeId,
                asDriver = true,
                cost = 0.0,
                unbecomeDriverOnCompletion = true
              ),
              createEmbodiedBeamLeg(routingRequestForWorkTrip, beamVehicle.toStreetVehicle, linkIds.reverse, 50d),
              EmbodiedBeamLeg.dummyLegAt(
                routingRequestForWorkTrip.departureTime + 300,
                routingRequestForWorkTrip.streetVehicles.find(_.mode == WALK).get.id,
                true,
                services.geo.utm2Wgs(routingRequestForWorkTrip.destinationUTM),
                WALK,
                personVehicle.vehicleTypeId
              )
            )
          )
        ),
        requestId = 1,
        request = None,
        isEmbodyWithCurrentTravelTime = false,
        triggerId = routingRequestForWorkTrip.triggerId
      )

      val modeChoiceForReturnFromWorkTrip = expectMsgType[ModeChoiceEvent]
      assert(modeChoiceForReturnFromWorkTrip.mode === "car")
      assert(modeChoiceForReturnFromWorkTrip.currentTourMode === "car_based")
      assert(modeChoiceForReturnFromWorkTrip.availableAlternatives === "CAR")

      lastSender ! ScheduleKillTrigger(lastSender, routingRequestForWorkTrip.triggerId)

      receiveWhile(500 millis) {
        case _: SchedulerMessage =>
        case x: Event            => println(x.toString)
        case x: HasTriggerId     => println(x.toString)
      }
    }

    it("should release vehicle at home boundary when next tour has no vehicle") {
      assertHomeBoundaryVehicleRelease(
        householdSize = 1,
        isEV = false,
        nextTourNamesVehicle = false,
        expectRelease = true
      )
    }

    it("should suppress vehicle release for single-driver non-EV household when next tour names vehicle") {
      assertHomeBoundaryVehicleRelease(
        householdSize = 1,
        isEV = false,
        nextTourNamesVehicle = true,
        expectRelease = false
      )
    }

    it("should release vehicle at home boundary for multi-driver household even when next tour names vehicle") {
      assertHomeBoundaryVehicleRelease(
        householdSize = 2,
        isEV = false,
        nextTourNamesVehicle = true,
        expectRelease = true
      )
    }

    it("should release vehicle at home boundary when EV even if next tour names vehicle") {
      assertHomeBoundaryVehicleRelease(
        householdSize = 1,
        isEV = true,
        nextTourNamesVehicle = true,
        expectRelease = true
      )
    }
  }

  private def assertHomeBoundaryVehicleRelease(
    householdSize: Int = 2,
    isEV: Boolean = false,
    nextTourNamesVehicle: Boolean = false,
    expectRelease: Boolean = true
  ): Unit = {
    val eventsManager = new EventsManagerImpl()
    eventsManager.addHandler(
      new BasicEventHandler {
        override def handleEvent(event: Event): Unit = {
          event match {
            case _: ModeChoiceEvent | _: TourModeChoiceEvent | _: ActivityEndEvent | _: ActivityStartEvent |
                _: ReplanningEvent =>
              self ! event
            case _ =>
          }
        }
      }
    )

    val vehicleId = Id.createVehicleId("car-dummyAgent-sequential")
    val vehicleTypeOriginal = beamScenario.vehicleTypes(Id.create("beamVilleCar", classOf[BeamVehicleType]))
    val vehicleType = if (isEV) {
      beamScenario.vehicleTypes(Id.create("BEV", classOf[BeamVehicleType]))
    } else {
      vehicleTypeOriginal
    }
    val beamVehicle = new BeamVehicle(vehicleId, new Powertrain(0.0), vehicleType)
    val vehicleManager = TestProbe()
    beamVehicle.setManager(Some(vehicleManager.ref))

    val household = householdsFactory.createHousehold(hoseHoldDummyId)
    val population = PopulationUtils.createPopulation(ConfigUtils.createConfig())
    val secondaryTourVehicle = if (nextTourNamesVehicle) Some(vehicleId) else None
    val person: Person =
      createTestPersonWithSequentialTours(
        Id.createPersonId("dummyAgent"),
        primaryTourMode = Some(BeamTourMode.fromString("car_based").get),
        primaryTourVehicle = Some(Id.create(vehicleId, classOf[BeamVehicle])),
        secondaryTourMode = if (nextTourNamesVehicle) Some(BeamTourMode.fromString("car_based").get) else None,
        secondaryTourVehicle = secondaryTourVehicle.map(v => Id.create(v, classOf[BeamVehicle])),
        householdSize = householdSize
      )
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
          parkingManager,
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
    receiveWhile(500 millis) { case _ => }
    val schedulerProbe = TestProbe()
    scheduler ! ScheduleTrigger(InitializeTrigger(0), householdActor)
    scheduler.tell(StartSchedule(0), schedulerProbe.ref)
    // Re-set manager to TestProbe so we can observe releases
    beamVehicle.setManager(Some(vehicleManager.ref))

    val linkIds = Array[Int](228, 206, 180, 178, 184, 102)

    def respondToTourRequest(routingRequest: RoutingRequest, targetAgent: ActorRef): Unit = {
      val personVehicle = routingRequest.streetVehicles.find(_.mode == WALK).get
      targetAgent ! RoutingResponse(
        Vector(
          EmbodiedBeamTrip(
            legs = Vector(
              EmbodiedBeamLeg.dummyLegAt(
                routingRequest.departureTime,
                personVehicle.id,
                false,
                services.geo.utm2Wgs(routingRequest.originUTM),
                WALK,
                personVehicle.vehicleTypeId
              ),
              createEmbodiedBeamLeg(routingRequest, beamVehicle.toStreetVehicle, linkIds, 50d),
              EmbodiedBeamLeg.dummyLegAt(
                routingRequest.departureTime + 250,
                personVehicle.id,
                true,
                services.geo.utm2Wgs(routingRequest.destinationUTM),
                WALK,
                personVehicle.vehicleTypeId
              )
            )
          ),
          EmbodiedBeamTrip(legs = Vector(createEmbodiedBeamLeg(routingRequest, personVehicle, linkIds, 150d)))
        ),
        requestId = routingRequest.requestId,
        request = Some(routingRequest),
        isEmbodyWithCurrentTravelTime = false,
        triggerId = routingRequest.triggerId
      )
    }

    def expectMsg[T: scala.reflect.ClassTag](max: FiniteDuration = 30.seconds): T = {
      fishForMessage(max) {
        case _: T => true
        case _    => false
      }.asInstanceOf[T]
    }

    def handleParkingAndWalk(parkingRoutingRequest: RoutingRequest, parkingAgent: ActorRef): Unit = {
      assert(parkingRoutingRequest.destinationUTM == parkingLocation)
      parkingAgent ! RoutingResponse(
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
                beamVehicleId = beamVehicle.id,
                beamVehicle.beamVehicleType.id,
                asDriver = true,
                cost = 0.0,
                unbecomeDriverOnCompletion = true
              )
            )
          )
        ),
        requestId = parkingRoutingRequest.requestId,
        request = Some(parkingRoutingRequest),
        isEmbodyWithCurrentTravelTime = false,
        triggerId = parkingRoutingRequest.triggerId
      )

      val walkFromParkingRoutingRequest = expectMsg[RoutingRequest]()
      val walkAgent = lastSender
      walkAgent ! RoutingResponse(
        itineraries = Vector(
          EmbodiedBeamTrip(
            legs = Vector(
              EmbodiedBeamLeg(
                beamLeg = BeamLeg(
                  startTime = walkFromParkingRoutingRequest.departureTime,
                  mode = BeamMode.WALK,
                  duration = 50,
                  travelPath = BeamPath(
                    linkIds = Array(80, 101),
                    linkTravelTime = Array(50, 50),
                    transitStops = None,
                    startPoint =
                      SpaceTime(services.geo.utm2Wgs(parkingLocation), walkFromParkingRoutingRequest.departureTime),
                    endPoint = SpaceTime(
                      services.geo.utm2Wgs(walkFromParkingRoutingRequest.destinationUTM),
                      walkFromParkingRoutingRequest.departureTime + 50
                    ),
                    distanceInM = 100d
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
        requestId = walkFromParkingRoutingRequest.requestId,
        request = Some(walkFromParkingRoutingRequest),
        isEmbodyWithCurrentTravelTime = false,
        triggerId = walkFromParkingRoutingRequest.triggerId
      )
    }

    try {
      val firstRoutingRequest = expectMsg[RoutingRequest]()
      val firstAgent = lastSender
      beamVehicle.setManager(Some(vehicleManager.ref))
      respondToTourRequest(firstRoutingRequest, firstAgent)
      val mce1 = expectMsg[ModeChoiceEvent]()
      assert(mce1.mode === "car")
      expectMsg[ActivityEndEvent]()
      val firstParkingReq = expectMsg[RoutingRequest]()
      handleParkingAndWalk(firstParkingReq, lastSender)
      expectMsg[ActivityStartEvent]() // Work activity starts

      val returnRoutingRequest = expectMsg[RoutingRequest]()
      val returnAgent = lastSender
      beamVehicle.setManager(Some(vehicleManager.ref))
      respondToTourRequest(returnRoutingRequest, returnAgent)
      val mce2 = expectMsg[ModeChoiceEvent]()
      assert(mce2.mode === "car")
      expectMsg[ActivityEndEvent]()
      val returnParkingReq = expectMsg[RoutingRequest]()
      handleParkingAndWalk(returnParkingReq, lastSender)
      expectMsg[ActivityStartEvent]() // Home activity starts (end of tour 1)

      if (expectRelease) {
        vehicleManager.fishForMessage(1.second) {
          case _: beam.agentsim.agents.household.HouseholdActor.ReleaseVehicle => true
          case _                                                               => false
        }
      } else {
        val msgs = vehicleManager.receiveWhile(300.millis) { case m => m }
        assert(
          !msgs.exists(_.isInstanceOf[beam.agentsim.agents.household.HouseholdActor.ReleaseVehicle]),
          s"Unexpected ReleaseVehicle in msgs: $msgs"
        )
      }
    } finally {
      scheduler.tell(ScheduleKillTrigger(scheduler, 0), schedulerProbe.ref)
      system.stop(householdActor)
      system.stop(scheduler)
      system.stop(parkingManager)
      receiveWhile(500 millis) { case _ =>
      }
    }
  }

  private def createTestPersonWithSequentialTours(
    personId: Id[Person],
    primaryTourMode: Option[BeamTourMode] = None,
    primaryTourVehicle: Option[Id[BeamVehicle]] = None,
    secondaryTourMode: Option[BeamTourMode] = None,
    secondaryTourVehicle: Option[Id[BeamVehicle]] = None,
    householdSize: Int = 2
  ) = {
    val person = PopulationUtils.getFactory.createPerson(personId)
    val attributesOfIndividual = AttributesOfIndividual(
      HouseholdAttributes("1", 200, householdSize, 400, 500),
      None,
      true,
      Vector(BeamMode.CAR, BeamMode.WALK, BeamMode.BIKE),
      Seq.empty,
      valueOfTime = 10000000.0,
      Some(42),
      Some(1234)
    )
    person.getCustomAttributes.put("beam-attributes", attributesOfIndividual)

    val plan = PopulationUtils.getFactory.createPlan()

    def addTourLeg(
      tourId: String,
      tourMode: Option[BeamTourMode],
      tripMode: Option[BeamMode],
      vehicle: Option[Id[BeamVehicle]]
    ): Unit = {
      val leg = PopulationUtils.createLeg(tripMode.map(_.matsimMode).getOrElse(""))
      leg.getAttributes.putAttribute("tour_id", tourId)
      tourMode.foreach(mode => leg.getAttributes.putAttribute("tour_mode", mode.value))
      vehicle.foreach(veh => leg.getAttributes.putAttribute("tour_vehicle", veh.toString))
      plan.addLeg(leg)
    }

    val homeActivity = PopulationUtils.createActivityFromLinkId("home", Id.createLinkId(1))
    homeActivity.setEndTime(28800)
    homeActivity.setCoord(homeLocation)
    plan.addActivity(homeActivity)

    addTourLeg("100", primaryTourMode, None, primaryTourVehicle)

    val workActivity = PopulationUtils.createActivityFromLinkId("work", Id.createLinkId(2))
    workActivity.setEndTime(43200)
    workActivity.setCoord(workLocation)
    plan.addActivity(workActivity)

    addTourLeg("100", primaryTourMode, None, primaryTourVehicle)

    val homeActivity2 = PopulationUtils.createActivityFromLinkId("home", Id.createLinkId(1))
    homeActivity2.setEndTime(48600)
    homeActivity2.setCoord(homeLocation)
    plan.addActivity(homeActivity2)

    addTourLeg("101", secondaryTourMode, None, secondaryTourVehicle)

    val otherActivity = PopulationUtils.createActivityFromLinkId("other", Id.createLinkId(3))
    otherActivity.setEndTime(61200)
    otherActivity.setCoord(workLocation)
    plan.addActivity(otherActivity)

    addTourLeg("101", secondaryTourMode, None, secondaryTourVehicle)

    val homeActivity3 = PopulationUtils.createActivityFromLinkId("home", Id.createLinkId(1))
    homeActivity3.setCoord(homeLocation)
    homeActivity3.setEndTime(65200)
    plan.addActivity(homeActivity3)

    person.addPlan(plan)
    person
  }

  private def createEmbodiedBeamLeg(
    routingRequest: RoutingRequest,
    personVehicle: VehicleProtocol.StreetVehicle,
    linkIds: Array[Int],
    tt: Double
  ): EmbodiedBeamLeg = {
    EmbodiedBeamLeg(
      BeamLeg(
        routingRequest.departureTime,
        personVehicle.mode,
        (tt * 5).toInt,
        BeamPath(
          linkIds,
          linkIds.map(_ => tt),
          None,
          SpaceTime(services.geo.utm2Wgs(routingRequest.originUTM), routingRequest.departureTime),
          SpaceTime(services.geo.utm2Wgs(routingRequest.originUTM), routingRequest.departureTime + (tt * 5).toInt),
          6000
        )
      ),
      personVehicle.id,
      personVehicle.vehicleTypeId,
      asDriver = true,
      0.0,
      unbecomeDriverOnCompletion = true
    )
  }

  private def createTestPerson(
    personId: Id[Person],
    vehicleId: Id[Vehicle],
    mode: Option[BeamMode],
    tourMode: Option[BeamTourMode],
    tourVehicle: Option[Id[BeamVehicle]] = None,
    withRoute: Boolean = true
  ) = {
    val person = PopulationUtils.getFactory.createPerson(personId)
    val attributesOfIndividual = AttributesOfIndividual(
      HouseholdAttributes("1", 200, 2, 400, 500),
      None,
      true,
      Vector(BeamMode.CAR, BeamMode.WALK, BeamMode.BIKE, BeamMode.WALK_TRANSIT),
      Seq.empty,
      valueOfTime = 10000000.0,
      Some(42),
      Some(1234)
    )
    person.getCustomAttributes.put("beam-attributes", attributesOfIndividual)
    mode.foreach(x => putDefaultBeamAttributes(person, Vector(x)))
    val plan = PopulationUtils.getFactory.createPlan()
    val homeActivity = PopulationUtils.createActivityFromLinkId("home", Id.createLinkId(1))
    homeActivity.setEndTime(28800) // 8:00:00 AM
    homeActivity.setCoord(homeLocation)
    plan.addActivity(homeActivity)
    val leg = PopulationUtils.createLeg(mode.map(_.matsimMode).getOrElse(""))
    leg.getAttributes.putAttribute("tour_id", "100")

    tourMode.map { mode =>
      leg.getAttributes.putAttribute("tour_mode", mode.value)
    }
    tourVehicle.map { veh =>
      leg.getAttributes.putAttribute("tour_vehicle", veh.toString)
    }

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
    val leg2 = PopulationUtils.createLeg(mode.map(_.matsimMode).getOrElse(""))
    leg2.getAttributes.putAttribute("tour_id", "100")
    leg2.getAttributes.putAttribute("tour_mode", tourMode.map(_.value).getOrElse(""))
    if (withRoute) {
      val route = RouteUtils.createLinkNetworkRouteImpl(
        Id.createLinkId(108),
        Array(206, 180, 178, 184, 102).reverse.map(Id.createLinkId(_)),
        Id.createLinkId(228)
      )
      route.setVehicleId(vehicleId)
      leg2.setRoute(route)
    }
    plan.addLeg(leg2)
    val homeActivity2 = PopulationUtils.createActivityFromLinkId("home", Id.createLinkId(1))
    homeActivity2.setCoord(homeLocation)
    homeActivity2.setEndTime(65200)
    plan.addActivity(homeActivity2)
    person.addPlan(plan)
    person
  }

  private def createTestPersonWithSubtour(
    personId: Id[Person],
    primaryTourMode: Option[BeamTourMode] = None,
    primaryTourTripMode: Option[BeamMode] = None,
    primaryTourVehicle: Option[Id[BeamVehicle]] = None,
    secondaryTourMode: Option[BeamTourMode] = None,
    secondaryTourTripMode: Option[BeamMode] = None,
    secondaryTourVehicle: Option[Id[BeamVehicle]] = None
  ) = {
    val person = PopulationUtils.getFactory.createPerson(personId)
    val attributesOfIndividual = AttributesOfIndividual(
      HouseholdAttributes("1", 200, 2, 400, 500),
      None,
      true,
      Vector(BeamMode.CAR, BeamMode.WALK, BeamMode.BIKE, BeamMode.WALK_TRANSIT),
      Seq.empty,
      valueOfTime = 10000000.0,
      Some(42),
      Some(1234)
    )
    person.getCustomAttributes.put("beam-attributes", attributesOfIndividual)

    val plan = PopulationUtils.getFactory.createPlan()
    val homeActivity = PopulationUtils.createActivityFromLinkId("home", Id.createLinkId(1))
    homeActivity.setEndTime(28800) // 8:00:00 AM
    homeActivity.setCoord(homeLocation)
    plan.addActivity(homeActivity)
    val leg = PopulationUtils.createLeg(primaryTourTripMode.map(_.matsimMode).getOrElse(""))
    leg.getAttributes.putAttribute("tour_id", "100")

    primaryTourMode.map { mode =>
      leg.getAttributes.putAttribute("tour_mode", mode.value)
    }
    primaryTourVehicle.map { veh =>
      leg.getAttributes.putAttribute("tour_vehicle", veh.toString)
    }
    plan.addLeg(leg)

    val workActivity = PopulationUtils.createActivityFromLinkId("work", Id.createLinkId(2))
    workActivity.setEndTime(43200) //12:00:00 PM
    workActivity.setCoord(workLocation)
    plan.addActivity(workActivity)

    val leg2 = PopulationUtils.createLeg(secondaryTourTripMode.map(_.matsimMode).getOrElse(""))
    leg2.getAttributes.putAttribute("tour_id", "101")

    secondaryTourMode.map { mode =>
      leg2.getAttributes.putAttribute("tour_mode", mode.value)
    }
    secondaryTourVehicle.map { veh =>
      leg2.getAttributes.putAttribute("tour_vehicle", veh.toString)
    }
    plan.addLeg(leg2)

    val otherActivity = PopulationUtils.createActivityFromLinkId("other", Id.createLinkId(3))
    otherActivity.setEndTime(48600) //1:30 PM (european style lunch)
    otherActivity.setCoord(workLocation)
    plan.addActivity(otherActivity)

    val leg3 = PopulationUtils.createLeg(primaryTourTripMode.map(_.matsimMode).getOrElse(""))
    leg3.getAttributes.putAttribute("tour_id", "101")

    primaryTourMode.map { mode =>
      leg3.getAttributes.putAttribute("tour_mode", mode.value)
    }
    primaryTourVehicle.map { veh =>
      leg3.getAttributes.putAttribute("tour_vehicle", veh.toString)
    }
    plan.addLeg(leg3)

    val workActivity2 = PopulationUtils.createActivityFromLinkId("work", Id.createLinkId(2))
    workActivity2.setEndTime(61200) //5:00:00 PM
    workActivity2.setCoord(workLocation)
    plan.addActivity(workActivity2)

    val leg4 = PopulationUtils.createLeg(primaryTourTripMode.map(_.matsimMode).getOrElse(""))
    leg4.getAttributes.putAttribute("tour_id", "100")

    primaryTourMode.map { mode =>
      leg4.getAttributes.putAttribute("tour_mode", mode.value)
    }
    primaryTourVehicle.map { veh =>
      leg4.getAttributes.putAttribute("tour_vehicle", veh.toString)
    }
    plan.addLeg(leg4)

    val homeActivity2 = PopulationUtils.createActivityFromLinkId("home", Id.createLinkId(1))
    homeActivity2.setCoord(homeLocation)
    homeActivity2.setEndTime(65200)
    plan.addActivity(homeActivity2)
    person.addPlan(plan)
    person
  }

  private def setupSubtourScenario(
    personIdStr: String,
    primaryTourMode: Option[BeamTourMode] = None,
    primaryTourVehicle: Option[Id[BeamVehicle]] = None,
    secondaryTourMode: Option[BeamTourMode] = None,
    secondaryTourVehicle: Option[Id[BeamVehicle]] = None,
    extraHouseholdVehicles: Seq[BeamVehicle] = Seq.empty,
    isParentCar: Boolean = true,
    vehicleManagerProbe: Option[TestProbe] = None
  ): (RoutingRequest, BeamVehicle, TestActorRef[BeamAgentScheduler], TestActorRef[HouseholdActor], ActorRef) = {
    val eventsManager = new EventsManagerImpl()
    eventsManager.addHandler(new BasicEventHandler {
      override def handleEvent(event: Event): Unit = event match {
        case _: ModeChoiceEvent | _: TourModeChoiceEvent | _: ActivityEndEvent | _: ActivityStartEvent |
            _: ReplanningEvent =>
          self ! event
        case _ =>
      }
    })

    val pId = Id.createPersonId(personIdStr)
    val car1Id = Id.createVehicleId(s"car1-$personIdStr")
    val vehicleType = beamScenario.vehicleTypes(Id.create("beamVilleCar", classOf[BeamVehicleType]))
    val beamVehicle1 = new BeamVehicle(car1Id, new Powertrain(0.0), vehicleType)

    val allVehicles = (Seq(beamVehicle1) ++ extraHouseholdVehicles).map(v => v.id -> v).toMap

    val household = householdsFactory.createHousehold(Id.create(s"hh-$personIdStr", classOf[Household]))
    val population = PopulationUtils.createPopulation(ConfigUtils.createConfig())

    val person: Person = createTestPersonWithSubtour(
      pId,
      primaryTourMode = primaryTourMode,
      primaryTourTripMode = if (isParentCar) None else Some(BeamMode.WALK),
      primaryTourVehicle = primaryTourVehicle.orElse(if (isParentCar) Some(car1Id) else None),
      secondaryTourMode = secondaryTourMode,
      secondaryTourTripMode = None,
      secondaryTourVehicle = secondaryTourVehicle
    )
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
          allVehicles,
          new Coord(0.0, 0.0),
          Vector(),
          Set.empty,
          new RouteHistory(beamConfig),
          VehiclesAdjustment.getVehicleAdjustment(beamScenario),
          configHolder
        )
      )
    )
    // Drain any leftover events from prior tests
    receiveWhile(500 millis) { case _ => }
    val vehProbe = vehicleManagerProbe.getOrElse(TestProbe())
    val schedulerProbe = TestProbe()
    try {
      scheduler ! ScheduleTrigger(InitializeTrigger(0), householdActor)
      scheduler.tell(StartSchedule(0), schedulerProbe.ref)

      val routingRequest = fishForMessage(30.seconds) {
        case req: RoutingRequest if req.personId.contains(pId) => true
        case _                                                 => false
      }.asInstanceOf[RoutingRequest]
      val personAgentRef = lastSender
      var activeParentCar = beamVehicle1
      val personVehicle = routingRequest.streetVehicles.find(_.mode == WALK).get
      val linkIds = Array[Int](228, 206, 180, 178, 184, 102)

      if (isParentCar) {
        val carStreetVeh = routingRequest.streetVehicles.find(_.mode == CAR).get
        val carBeamVeh = allVehicles(carStreetVeh.id)
        activeParentCar = carBeamVeh
        // Re-set manager to test probe so vehicle idle notifications and release messages go to probe,
        // not self, avoiding pollution of self's mailbox while allowing release verification.
        // This is done after actor startup because HouseholdFleetManager assigns itself as vehicle manager during initialization.
        carBeamVeh.setManager(Some(vehProbe.ref))
        extraHouseholdVehicles.foreach(_.setManager(Some(vehProbe.ref)))
        personAgentRef ! RoutingResponse(
          Vector(
            EmbodiedBeamTrip(
              legs = Vector(
                EmbodiedBeamLeg.dummyLegAt(
                  routingRequest.departureTime,
                  personVehicle.id,
                  false,
                  services.geo.utm2Wgs(routingRequest.originUTM),
                  WALK,
                  personVehicle.vehicleTypeId
                ),
                createEmbodiedBeamLeg(routingRequest, carBeamVeh.toStreetVehicle, linkIds, 50d),
                EmbodiedBeamLeg.dummyLegAt(
                  routingRequest.departureTime + 250,
                  personVehicle.id,
                  true,
                  services.geo.utm2Wgs(routingRequest.destinationUTM),
                  WALK,
                  personVehicle.vehicleTypeId
                )
              )
            )
          ),
          requestId = routingRequest.requestId,
          request = Some(routingRequest),
          isEmbodyWithCurrentTravelTime = false,
          triggerId = routingRequest.triggerId
        )
        fishForMessage(30.seconds) {
          case mce: ModeChoiceEvent if mce.personId == pId => true
          case _                                           => false
        }
        fishForMessage(30.seconds) {
          case aee: ActivityEndEvent if aee.getPersonId == pId => true
          case _                                               => false
        }

        val parkingRoutingRequest = fishForMessage(30.seconds) {
          case req: RoutingRequest if req.personId.contains(pId) => true
          case _                                                 => false
        }.asInstanceOf[RoutingRequest]
        val parkingAgentRef = lastSender
        parkingAgentRef ! RoutingResponse(
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
                      endPoint = SpaceTime(
                        services.geo.utm2Wgs(parkingLocation),
                        parkingRoutingRequest.departureTime + 200
                      ),
                      distanceInM = 1000d
                    )
                  ),
                  beamVehicleId = activeParentCar.id,
                  Id.create("TRANSIT-TYPE-DEFAULT", classOf[BeamVehicleType]),
                  asDriver = true,
                  cost = 0.0,
                  unbecomeDriverOnCompletion = true
                )
              )
            )
          ),
          requestId = parkingRoutingRequest.requestId,
          request = Some(parkingRoutingRequest),
          isEmbodyWithCurrentTravelTime = false,
          triggerId = parkingRoutingRequest.triggerId
        )

        val walkFromParkingRoutingRequest = fishForMessage(30.seconds) {
          case req: RoutingRequest if req.personId.contains(pId) => true
          case _                                                 => false
        }.asInstanceOf[RoutingRequest]
        val walkAgentRef = lastSender
        walkAgentRef ! RoutingResponse(
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
                      startPoint = SpaceTime(
                        services.geo.utm2Wgs(parkingLocation),
                        walkFromParkingRoutingRequest.departureTime
                      ),
                      endPoint = SpaceTime(
                        services.geo.utm2Wgs(walkFromParkingRoutingRequest.destinationUTM),
                        walkFromParkingRoutingRequest.departureTime + 200
                      ),
                      distanceInM = 1000d
                    )
                  ),
                  beamVehicleId = personVehicle.id,
                  personVehicle.vehicleTypeId,
                  asDriver = true,
                  cost = 0.0,
                  unbecomeDriverOnCompletion = true
                )
              )
            )
          ),
          requestId = walkFromParkingRoutingRequest.requestId,
          request = Some(walkFromParkingRoutingRequest),
          isEmbodyWithCurrentTravelTime = false,
          triggerId = walkFromParkingRoutingRequest.triggerId
        )
      } else {
        personAgentRef ! RoutingResponse(
          Vector(
            EmbodiedBeamTrip(
              legs = Vector(
                EmbodiedBeamLeg(
                  beamLeg = BeamLeg(
                    startTime = routingRequest.departureTime,
                    mode = BeamMode.WALK,
                    duration = 100,
                    travelPath = BeamPath(
                      linkIds = Array(228, 206, 180, 178, 184, 102),
                      linkTravelTime = Array(0, 20, 20, 20, 20, 20),
                      transitStops = None,
                      startPoint = SpaceTime(
                        services.geo.utm2Wgs(routingRequest.originUTM),
                        routingRequest.departureTime
                      ),
                      endPoint = SpaceTime(
                        services.geo.utm2Wgs(routingRequest.destinationUTM),
                        routingRequest.departureTime + 100
                      ),
                      distanceInM = 1000d
                    )
                  ),
                  beamVehicleId = personVehicle.id,
                  personVehicle.vehicleTypeId,
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
        fishForMessage(30.seconds) {
          case mce: ModeChoiceEvent if mce.personId == pId => true
          case _                                           => false
        }
        fishForMessage(30.seconds) {
          case aee: ActivityEndEvent if aee.getPersonId == pId => true
          case _                                               => false
        }
      }

      fishForMessage(30.seconds) {
        case ase: ActivityStartEvent if ase.getPersonId == pId => true
        case _                                                 => false
      }
      val subtourRoutingRequest = fishForMessage(30.seconds) {
        case req: RoutingRequest if req.personId.contains(pId) => true
        case _                                                 => false
      }.asInstanceOf[RoutingRequest]
      (subtourRoutingRequest, activeParentCar, scheduler, householdActor, parkingManager)
    } catch {
      case NonFatal(e) =>
        killSchedulerAndDrain(scheduler, householdActor, parkingManager)
        throw e
    }
  }

  private def killSchedulerAndDrain(
    scheduler: TestActorRef[BeamAgentScheduler],
    householdActor: ActorRef,
    parkingManager: ActorRef
  ): Unit = {
    val probe = TestProbe()
    scheduler.tell(ScheduleKillTrigger(scheduler, 0), probe.ref)
    system.stop(householdActor)
    system.stop(scheduler)
    system.stop(parkingManager)
    receiveWhile(500 millis) { case _ =>
    }
  }

  describe("Subtour vehicle enforcement (A1 / B3)") {
    it("should allow only parent car on subtour of car-based tour") {
      val (subtourReq, parentCar, scheduler, householdActor, parkingManager) = setupSubtourScenario(
        personIdStr = "b3-case1",
        primaryTourMode = Some(CAR_BASED),
        primaryTourVehicle = None,
        isParentCar = true
      )
      try {
        val personalVehicles =
          subtourReq.streetVehicles.filterNot(v =>
            v.mode == WALK || v.id.toString.contains("sharedVehicle") || BeamVehicle.isSharedTeleportationVehicle(v.id)
          )
        assert(personalVehicles.map(_.id) == Vector(parentCar.id))

        val personVehicle = subtourReq.streetVehicles.find(_.mode == WALK).get
        val linkIds = Array[Int](228, 206, 180, 178, 184, 102)
        lastSender ! RoutingResponse(
          itineraries = Vector(
            EmbodiedBeamTrip(
              legs = Vector(
                EmbodiedBeamLeg.dummyLegAt(
                  subtourReq.departureTime,
                  personVehicle.id,
                  false,
                  services.geo.utm2Wgs(subtourReq.originUTM),
                  WALK,
                  personVehicle.vehicleTypeId
                ),
                createEmbodiedBeamLeg(subtourReq, parentCar.toStreetVehicle, linkIds, 50d),
                EmbodiedBeamLeg.dummyLegAt(
                  subtourReq.departureTime + 250,
                  personVehicle.id,
                  true,
                  services.geo.utm2Wgs(subtourReq.destinationUTM),
                  WALK,
                  personVehicle.vehicleTypeId
                )
              )
            )
          ),
          requestId = subtourReq.requestId,
          request = Some(subtourReq),
          isEmbodyWithCurrentTravelTime = false,
          triggerId = subtourReq.triggerId
        )

        val tmc = fishForMessage(30.seconds) {
          case ev: TourModeChoiceEvent if ev.personId.toString == "b3-case1" => true
          case _                                                             => false
        }.asInstanceOf[TourModeChoiceEvent]
        assert(tmc.availablePersonalStreetVehiclesString.contains(parentCar.beamVehicleType.id.toString))
      } finally {
        killSchedulerAndDrain(scheduler, householdActor, parkingManager)
      }
    }

    it("should not allow second household vehicle on subtour even if named in subtour leg") {
      val vehicleType = beamScenario.vehicleTypes(Id.create("beamVilleCar", classOf[BeamVehicleType]))
      val secondCar = new BeamVehicle(Id.createVehicleId("car2-b3-case2"), new Powertrain(0.0), vehicleType)

      val (subtourReq, parentCar, scheduler, householdActor, parkingManager) = setupSubtourScenario(
        personIdStr = "b3-case2",
        primaryTourMode = Some(CAR_BASED),
        primaryTourVehicle = None,
        secondaryTourMode = None,
        secondaryTourVehicle = Some(secondCar.id),
        extraHouseholdVehicles = Seq(secondCar),
        isParentCar = true
      )
      try {
        assert(!subtourReq.streetVehicles.exists(_.id == secondCar.id))
        val personalVehicles =
          subtourReq.streetVehicles.filterNot(v =>
            v.mode == WALK || v.id.toString.contains("sharedVehicle") || BeamVehicle.isSharedTeleportationVehicle(v.id)
          )
        assert(personalVehicles.map(_.id) == Vector(parentCar.id))
      } finally {
        killSchedulerAndDrain(scheduler, householdActor, parkingManager)
      }
    }

    it("should see no personal vehicles on subtour when parent tour is walk-based") {
      val (subtourReq, _, scheduler, householdActor, parkingManager) = setupSubtourScenario(
        personIdStr = "b3-case3",
        primaryTourMode = Some(WALK_BASED),
        primaryTourVehicle = None,
        isParentCar = false
      )
      try {
        val personalVehicles =
          subtourReq.streetVehicles.filterNot(v =>
            v.mode == WALK || v.id.toString.contains("sharedVehicle") || BeamVehicle.isSharedTeleportationVehicle(v.id)
          )
        assert(personalVehicles.isEmpty)
      } finally {
        killSchedulerAndDrain(scheduler, householdActor, parkingManager)
      }
    }

    it("should allow and adopt replacement vehicle on subtour when parent car is missing") {
      val vehicleType = beamScenario.vehicleTypes(Id.create("beamVilleCar", classOf[BeamVehicleType]))
      val emergencyCar = new BeamVehicle(Id.createVehicleId("agent-emergency-car"), new Powertrain(0.0), vehicleType)
      val secondCar = new BeamVehicle(Id.createVehicleId("second-car"), new Powertrain(0.0), vehicleType)

      // When parent car is missing and replacement is adopted into parent tour strategy:
      val adoptedParentVehicle = Some(emergencyCar.id)
      assert(
        ChoosesMode.isVehicleAllowed(emergencyCar, parentTourVehicleId = adoptedParentVehicle, onSubTour = true),
        "Adopted replacement vehicle must be allowed on subtour"
      )
      assert(
        !ChoosesMode.isVehicleAllowed(secondCar, parentTourVehicleId = adoptedParentVehicle, onSubTour = true),
        "Unadopted vehicle must NOT be allowed on subtour"
      )
    }

    it("should recover adopted replacement vehicle from available vehicles on subtour") {
      val vehicleType = beamScenario.vehicleTypes(Id.create("beamVilleCar", classOf[BeamVehicleType]))
      val emergencyCar = new BeamVehicle(Id.createVehicleId("agent-emergency-car"), new Powertrain(0.0), vehicleType)
      val (recoveredFromItin, recoveredFromAvail) = ChoosesMode.recoverTourVehicle(
        tourMode = CAR_BASED,
        distinctAvailableVehicles = Vector(ActualVehicle(emergencyCar)),
        firstLegItineraries = Vector.empty,
        parentTourVehicleId = Some(emergencyCar.id),
        onSubTour = true
      )
      assert(recoveredFromItin.isEmpty)
      assert(recoveredFromAvail === Some(emergencyCar.id))
    }

    it("should not save shared bike or scooter chosen on walk tour as tour vehicle") {
      val sharedBikeId = Id.createVehicleId("sharedVehicle-bike-1")
      val sanitized =
        ChoosesMode.sanitizeTourVehicleId(Some(sharedBikeId), parentTourVehicleId = None, onSubTour = false)
      assert(sanitized.isEmpty, "Shared vehicle must not be stored as tour vehicle")

      val sharedTeleportId = Id.createVehicleId("sharedTeleportationVehicle-bike-2")
      val sanitizedTeleport =
        ChoosesMode.sanitizeTourVehicleId(Some(sharedTeleportId), parentTourVehicleId = None, onSubTour = false)
      assert(sanitizedTeleport.isEmpty, "Shared teleportation vehicle must not be stored as tour vehicle")
    }
  }

  describe("Emergency vehicle cleanup (A3)") {
    it("should drop and release unreferenced emergency vehicle on car-based tour to its manager") {
      val vehicleType = beamScenario.vehicleTypes(Id.create("beamVilleCar", classOf[BeamVehicleType]))
      val emergencyCar = new BeamVehicle(Id.createVehicleId("agent-emergency-a3"), new Powertrain(0.0), vehicleType)
      val vehicleProbe = TestProbe()

      val (subtourReq, _, scheduler, householdActor, parkingManager) = setupSubtourScenario(
        personIdStr = "a3-cleanup-case",
        primaryTourMode = Some(CAR_BASED),
        primaryTourVehicle = None,
        extraHouseholdVehicles = Seq(emergencyCar),
        isParentCar = true,
        vehicleManagerProbe = Some(vehicleProbe)
      )
      try {
        assert(!subtourReq.streetVehicles.exists(_.id == emergencyCar.id))
        vehicleProbe.fishForMessage(3.seconds) {
          case ReleaseVehicle(veh, _) => veh.id == emergencyCar.id
          case _                      => false
        }
      } finally {
        killSchedulerAndDrain(scheduler, householdActor, parkingManager)
      }
    }

    it("should clear stale emergency tour vehicle when not present in beamVehicles on entering ChoosingMode") {
      val (subtourReq, parentCar, scheduler, householdActor, parkingManager) = setupSubtourScenario(
        personIdStr = "a3-stale-case",
        primaryTourMode = Some(CAR_BASED),
        primaryTourVehicle = Some(Id.createVehicleId("a3-stale-case-emergency-missing")),
        extraHouseholdVehicles = Seq.empty,
        isParentCar = true
      )
      try {
        val personalVehicles =
          subtourReq.streetVehicles.filterNot(v =>
            v.mode == WALK || v.id.toString.contains("sharedVehicle") || BeamVehicle.isSharedTeleportationVehicle(v.id)
          )
        assert(personalVehicles.map(_.id) == Vector(parentCar.id))
      } finally {
        killSchedulerAndDrain(scheduler, householdActor, parkingManager)
      }
    }
  }

  describe("Recovery fallback tests (B4)") {
    lazy val vehicleType = beamScenario.vehicleTypes(Id.create("beamVilleCar", classOf[BeamVehicleType]))
    lazy val parentCar = new BeamVehicle(Id.createVehicleId("parent-car"), new Powertrain(0.0), vehicleType)
    lazy val secondCar = new BeamVehicle(Id.createVehicleId("second-car"), new Powertrain(0.0), vehicleType)

    it("should not recover non-allowed vehicle from itineraries as tour vehicle on subtour") {
      val unallowedItinerary = EmbodiedBeamTrip(
        legs = Vector(
          EmbodiedBeamLeg(
            beamLeg = BeamLeg(
              startTime = 1000,
              mode = BeamMode.CAR,
              duration = 100,
              travelPath = BeamPath(
                Array(1, 2),
                Array(0, 100),
                None,
                SpaceTime(0.0, 0.0, 1000),
                SpaceTime(10.0, 10.0, 1100),
                1000d
              )
            ),
            beamVehicleId = secondCar.id,
            beamVehicleTypeId = vehicleType.id,
            asDriver = true,
            cost = 0.0,
            unbecomeDriverOnCompletion = true
          )
        )
      )

      val (recoveredFromItin, _) = ChoosesMode.recoverTourVehicle(
        tourMode = CAR_BASED,
        distinctAvailableVehicles = Vector(ActualVehicle(parentCar), ActualVehicle(secondCar)),
        firstLegItineraries = Vector(unallowedItinerary),
        parentTourVehicleId = Some(parentCar.id),
        onSubTour = true
      )

      assert(recoveredFromItin.isEmpty, "Non-allowed vehicle must NOT be recovered from itineraries on subtour")
    }

    it(
      "should recover vehicle from distinctAvailableVehicles when effectiveTourVehicle is None and tourMode is vehicle-based"
    ) {
      val (recoveredFromItin, recoveredFromAvail) = ChoosesMode.recoverTourVehicle(
        tourMode = CAR_BASED,
        distinctAvailableVehicles = Vector(ActualVehicle(parentCar)),
        firstLegItineraries = Vector.empty,
        parentTourVehicleId = Some(parentCar.id),
        onSubTour = true
      )

      assert(recoveredFromItin.isEmpty)
      assert(recoveredFromAvail === Some(parentCar.id), "Allowed vehicle IS recovered from distinct available vehicles")
    }

    it("should not recover vehicle from available vehicles when multiple eligible vehicles exist") {
      val (recoveredFromItin, recoveredFromAvail) = ChoosesMode.recoverTourVehicle(
        tourMode = CAR_BASED,
        distinctAvailableVehicles = Vector(ActualVehicle(parentCar), ActualVehicle(secondCar)),
        firstLegItineraries = Vector.empty,
        parentTourVehicleId = None,
        onSubTour = false
      )
      assert(recoveredFromItin.isEmpty)
      assert(recoveredFromAvail.isEmpty, "Ambiguous: two eligible vehicles must result in nothing recovered")
    }
  }

  describe("Matsim plan leg route saving (A2)") {
    val originLink = Id.create(1, classOf[Link])
    val destLink = Id.create(3, classOf[Link])

    it("should deduplicate consecutive duplicate link IDs in parking-split car trip") {
      val leg = PopulationUtils.createLeg("car")
      val drivingLeg1 = EmbodiedBeamLeg(
        beamLeg = BeamLeg(
          startTime = 100,
          mode = BeamMode.CAR,
          duration = 50,
          travelPath = BeamPath(
            linkIds = Array(1, 2),
            linkTravelTime = Array(0, 50),
            transitStops = None,
            startPoint = SpaceTime(0.0, 0.0, 100),
            endPoint = SpaceTime(10.0, 10.0, 150),
            distanceInM = 500d
          )
        ),
        beamVehicleId = Id.createVehicleId("car-a2"),
        beamVehicleTypeId = Id.create("beamVilleCar", classOf[BeamVehicleType]),
        asDriver = true,
        cost = 0.0,
        unbecomeDriverOnCompletion = false
      )
      val drivingLeg2 = EmbodiedBeamLeg(
        beamLeg = BeamLeg(
          startTime = 150,
          mode = BeamMode.CAR,
          duration = 50,
          travelPath = BeamPath(
            linkIds = Array(2, 3),
            linkTravelTime = Array(0, 50),
            transitStops = None,
            startPoint = SpaceTime(10.0, 10.0, 150),
            endPoint = SpaceTime(20.0, 20.0, 200),
            distanceInM = 500d
          )
        ),
        beamVehicleId = Id.createVehicleId("car-a2"),
        beamVehicleTypeId = Id.create("beamVilleCar", classOf[BeamVehicleType]),
        asDriver = true,
        cost = 0.0,
        unbecomeDriverOnCompletion = true
      )
      val parkingSplitTrip = EmbodiedBeamTrip(
        legs = Vector(drivingLeg1, drivingLeg2)
      )

      ChoosesMode.updateMatsimPlanLegRoute(leg, parkingSplitTrip, originLink, destLink, beamScenario.network)

      val netRoute = leg.getRoute.asInstanceOf[NetworkRoute]
      val linkIds = JavaConverters.asScalaBuffer(netRoute.getLinkIds).map(_.toString.toInt).toVector
      assert(
        !linkIds.sliding(2).exists(w => w.length == 2 && w(0) == w(1)),
        s"Found consecutive duplicates in $linkIds"
      )
      assert(netRoute.getDistance == 1000d, "Distance must be sum of driving legs distanceInM")
    }

    it("should save GenericRouteImpl for non-replayable modes like DRIVE_TRANSIT or WALK") {
      val leg = PopulationUtils.createLeg("walk")
      val transitTrip = EmbodiedBeamTrip(
        legs = Vector(
          EmbodiedBeamLeg.dummyLegAt(
            100,
            Id.createVehicleId("body"),
            false,
            SpaceTime(0.0, 0.0, 100).loc,
            BeamMode.WALK,
            Id.create("bodyType", classOf[BeamVehicleType])
          )
        )
      )
      ChoosesMode.updateMatsimPlanLegRoute(leg, transitTrip, originLink, destLink, beamScenario.network)
      assert(leg.getRoute.isInstanceOf[GenericRouteImpl])
    }
  }

  override def afterAll(): Unit = {
    super.afterAll()
  }

  after {
    import scala.concurrent.duration._
    import scala.language.postfixOps
    // Prevent unconsumed messages or completion notices from bleeding into the next test
    receiveWhile(500 millis) { case _ =>
    }
  }

}
