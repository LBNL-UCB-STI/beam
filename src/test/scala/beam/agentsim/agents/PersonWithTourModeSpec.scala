package beam.agentsim.agents

import akka.actor.{ActorSystem, Props}
import akka.pattern.{ask, pipe}
import akka.testkit.{ImplicitSender, TestActorRef, TestKitBase, TestProbe}
import akka.util.Timeout

import scala.concurrent.duration._
import beam.agentsim.agents.PersonTestUtil._
import beam.agentsim.agents.choice.logit.TourModeChoiceModel
import beam.agentsim.agents.choice.mode.ModeChoiceUniformRandom
import beam.agentsim.agents.household.HouseholdActor.{HouseholdActor, MobilityStatusInquiry, MobilityStatusResponse}
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
import org.matsim.core.population.routes.RouteUtils
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
        akka.test.timefactor = 20
        """
    )
    .withFallback(testConfig("test/input/beamville/beam.conf"))
    .resolve()

  lazy implicit val system: ActorSystem = ActorSystem("PersonWithTourModeSpec", config)

  override def outputDirPath: String = TestConfigUtils.testOutputDir

  private val householdsFactory: HouseholdsFactoryImpl = new HouseholdsFactoryImpl()

  private lazy val modeChoiceCalculator = new ModeChoiceUniformRandom(beamConfig)
  private lazy val tourModeChoiceCalculator = new TourModeChoiceModel(beamConfig)

  val homeLocation = new Coord(170308.4, 2964.6474)
  val workLocation = new Coord(169346.4, 876.7536)
  val otherLocation = new Coord(168346.4, 1276.7536)

  describe("A PersonAgent") {

    val hoseHoldDummyId = Id.create("dummy", classOf[Household])

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

      // The agent will ask for current travel times for a route it already knows.
      val tmc = expectMsgType[TourModeChoiceEvent]
      // Make sure that they chose a car_based tour
      assert(tmc.tourMode === "car_based")
      // Make sure it didn't actually go through the process of calculating utilities b/c it didn't have to
      assert(tmc.tourModeToUtilityString === "")
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
      val mce = expectMsgType[ModeChoiceEvent]
      assert(mce.currentTourMode === chosenTourMode)
      expectMsgType[ActivityEndEvent]
      expectMsgType[PersonDepartureEvent]

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
      val managerId = VehicleManager.createOrGetReservedFor("shared-fleet-1", VehicleManager.TypeEnum.Shared).managerId
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
      val tmc = expectMsgType[TourModeChoiceEvent]
      val rr2 = expectMsgType[RoutingRequest]
      expectMsgType[ActivityEndEvent]
    }
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
      HouseholdAttributes("1", 200, 300, 400, 500),
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
    leg.getAttributes.putAttribute("tour_id", 100)

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
    leg2.getAttributes.putAttribute("tour_id", 100)
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
      HouseholdAttributes("1", 200, 300, 400, 500),
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
    leg.getAttributes.putAttribute("tour_id", 100)

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
    leg2.getAttributes.putAttribute("tour_id", 101)

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
    leg3.getAttributes.putAttribute("tour_id", 101)

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
    leg4.getAttributes.putAttribute("tour_id", 100)

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
