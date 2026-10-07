package beam.agentsim.agents

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.{ask, pipe}
import akka.testkit.{ImplicitSender, TestActorRef, TestKitBase, TestProbe}
import akka.util.Timeout
import beam.agentsim.agents.planning.Strategy.TourModeChoiceStrategy

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
import beam.agentsim.scheduler.{BeamAgentScheduler, HasTriggerId, Trigger}
import beam.agentsim.scheduler.Trigger.TriggerWithId
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
import beam.agentsim.agents.modalbehaviors.DrivesVehicle.{ActualVehicle, Token, VehicleOrToken}
import beam.agentsim.agents.modalbehaviors.ChoosesMode
import beam.router.RouteHistory
import beam.router.TourModes.BeamTourMode
import beam.router.TourModes.BeamTourMode.{BIKE_BASED, CAR_BASED, WALK_BASED}
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
import org.matsim.households.{Household, HouseholdsFactoryImpl, Income, IncomeImpl}
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
        beam.agentsim.agents.vehicles.generateEmergencyHouseholdVehicleWhenPlansRequireIt = true
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
      try {
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
                          .copy(time =
                            embodyRequest.leg.startTime + (embodyRequest.leg.travelPath.linkIds.size - 1) * 50
                          )
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
      } finally {
        killSchedulerAndDrain(scheduler, householdActor, parkingManager)
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
      try {
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
                          .copy(time =
                            embodyRequest.leg.startTime + (embodyRequest.leg.travelPath.linkIds.size - 1) * 50
                          )
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
      } finally {
        killSchedulerAndDrain(scheduler, householdActor, parkingManager)
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
      try {
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
      } finally {
        killSchedulerAndDrain(scheduler, householdActor, parkingManager)
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
      try {
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
      } finally {
        killSchedulerAndDrain(scheduler, householdActor, parkingManager)
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
      try {
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
      } finally {
        killSchedulerAndDrain(scheduler, householdActor, parkingManager)
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
      try {
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
      } finally {
        killSchedulerAndDrain(scheduler, householdActor, parkingManager)
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
      try {
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
      } finally {
        killSchedulerAndDrain(scheduler, householdActor, parkingManager)
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
      try {
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
      } finally {
        killSchedulerAndDrain(scheduler, householdActor, parkingManager)
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

    it("should bring its own car home with an expensive car trip when the last trip of a car tour has no route") {
      assertHomeBoundaryVehicleRelease(
        householdSize = 1,
        isEV = false,
        nextTourNamesVehicle = false,
        expectRelease = true,
        failReturnRoute = true
      )
    }

    it("should bring its own bike home with an expensive bike trip when the last trip of a bike tour has no route") {
      assertHomeBoundaryVehicleRelease(
        householdSize = 1,
        isEV = false,
        nextTourNamesVehicle = false,
        expectRelease = true,
        failReturnRoute = true,
        tourMode = BIKE_BASED,
        vehicleTypeIdStr = "Bicycle"
      )
    }
  }

  private def assertHomeBoundaryVehicleRelease(
    householdSize: Int = 2,
    isEV: Boolean = false,
    nextTourNamesVehicle: Boolean = false,
    expectRelease: Boolean = true,
    failReturnRoute: Boolean = false,
    tourMode: BeamTourMode = CAR_BASED,
    vehicleTypeIdStr: String = "beamVilleCar"
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

    val testKey = s"$householdSize-$isEV-$nextTourNamesVehicle-$failReturnRoute-$tourMode"
    val vehicleId = Id.createVehicleId(s"${if (tourMode == BIKE_BASED) "bike" else "car"}-dummyAgent-seq-$testKey")
    val vehicleTypeOriginal = beamScenario.vehicleTypes(Id.create(vehicleTypeIdStr, classOf[BeamVehicleType]))
    val vehicleType = if (isEV) {
      beamScenario.vehicleTypes(Id.create("BEV", classOf[BeamVehicleType]))
    } else {
      vehicleTypeOriginal
    }
    val beamVehicle = new BeamVehicle(vehicleId, new Powertrain(0.0), vehicleType)
    val vehicleManager = TestProbe()

    val household = householdsFactory.createHousehold(Id.create(s"dummy-hh-seq-$testKey", classOf[Household]))
    val population = PopulationUtils.createPopulation(ConfigUtils.createConfig())
    val pId = Id.createPersonId(s"dummyAgent-seq-$householdSize-$isEV-$nextTourNamesVehicle-$tourMode")
    val secondaryTourVehicle = if (nextTourNamesVehicle) Some(vehicleId) else None
    val person: Person =
      createTestPersonWithSequentialTours(
        pId,
        primaryTourMode = Some(tourMode),
        primaryTourVehicle = Some(Id.create(vehicleId, classOf[BeamVehicle])),
        secondaryTourMode = if (nextTourNamesVehicle) Some(tourMode) else None,
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
      val runtimeClass = implicitly[scala.reflect.ClassTag[T]].runtimeClass
      fishForMessage(max) {
        case req: RoutingRequest if runtimeClass.isAssignableFrom(classOf[RoutingRequest]) =>
          req.personId.contains(pId)
        case evt: Event if runtimeClass.isAssignableFrom(evt.getClass) =>
          evt match {
            case mce: ModeChoiceEvent    => mce.personId == pId
            case aee: ActivityEndEvent   => aee.getPersonId == pId
            case ase: ActivityStartEvent => ase.getPersonId == pId
            case _                       => true
          }
        case m if runtimeClass.isInstance(m) => true
        case _                               => false
      }.asInstanceOf[T]
    }

    def expectRoutingRequest(max: FiniteDuration = 30.seconds): (RoutingRequest, ActorRef) = {
      var senderRef: ActorRef = null
      val req = fishForMessage(max) {
        case req: RoutingRequest if req.personId.contains(pId) =>
          senderRef = lastSender
          true
        case _ => false
      }.asInstanceOf[RoutingRequest]
      (req, senderRef)
    }

    def handleParkingAndWalk(parkingRoutingRequest: RoutingRequest, parkingAgent: ActorRef): Unit = {
      val expectedDest = if (tourMode == BIKE_BASED) parkingRoutingRequest.destinationUTM else parkingLocation
      assert(parkingRoutingRequest.destinationUTM == expectedDest)
      parkingAgent ! RoutingResponse(
        itineraries = Vector(
          EmbodiedBeamTrip(
            legs = Vector(
              EmbodiedBeamLeg(
                beamLeg = BeamLeg(
                  startTime = parkingRoutingRequest.departureTime,
                  mode = if (tourMode == BIKE_BASED) BeamMode.BIKE else BeamMode.CAR,
                  duration = 50,
                  travelPath = BeamPath(
                    linkIds = Array(142, 60, 58, 62, 80),
                    linkTravelTime = Array(50, 50, 50, 50, 50),
                    transitStops = None,
                    startPoint = SpaceTime(
                      services.geo.utm2Wgs(parkingRoutingRequest.originUTM),
                      parkingRoutingRequest.departureTime
                    ),
                    endPoint = SpaceTime(services.geo.utm2Wgs(expectedDest), parkingRoutingRequest.departureTime + 200),
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

      val (walkFromParkingRoutingRequest, walkAgent) = expectRoutingRequest()
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
                      SpaceTime(services.geo.utm2Wgs(expectedDest), walkFromParkingRoutingRequest.departureTime),
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
      val expectedMode = if (tourMode == BIKE_BASED) "bike" else "car"
      val (firstRoutingRequest, firstAgent) = expectRoutingRequest()
      beamVehicle.setManager(Some(vehicleManager.ref))
      respondToTourRequest(firstRoutingRequest, firstAgent)
      val mce1 = expectMsg[ModeChoiceEvent]()
      assert(mce1.mode === expectedMode)
      expectMsg[ActivityEndEvent]()
      if (tourMode == CAR_BASED) {
        val (firstParkingReq, firstParkingAgent) = expectRoutingRequest()
        handleParkingAndWalk(firstParkingReq, firstParkingAgent)
      }
      expectMsg[ActivityStartEvent]() // Work activity starts

      val (returnRoutingRequest, returnAgent) = expectRoutingRequest()
      beamVehicle.setManager(Some(vehicleManager.ref))
      if (failReturnRoute) {
        assert(returnRoutingRequest.streetVehicles.exists(_.id == beamVehicle.id))
        returnAgent ! RoutingResponse(
          Vector.empty,
          returnRoutingRequest.requestId,
          Some(returnRoutingRequest),
          isEmbodyWithCurrentTravelTime = false,
          triggerId = returnRoutingRequest.triggerId
        )
        // The person must keep their vehicle and drive it home (expensively), not abandon it at work
        val mce2 = expectMsg[ModeChoiceEvent]()
        assert(mce2.mode === expectedMode, s"Expected an expensive $expectedMode trip home, got ${mce2.mode}")
        assert(mce2.chosenTrip.vehiclesInTrip.contains(beamVehicle.id))
        assert(
          !vehicleManager.receiveWhile(300.millis) { case m => m }.exists(_.isInstanceOf[ReleaseVehicle]),
          s"$expectedMode must not be released away from home"
        )
      } else {
        respondToTourRequest(returnRoutingRequest, returnAgent)
        val mce2 = expectMsg[ModeChoiceEvent]()
        assert(mce2.mode === expectedMode)
        expectMsg[ActivityEndEvent]()
        val (returnParkingReq, returnParkingAgent) = expectRoutingRequest()
        handleParkingAndWalk(returnParkingReq, returnParkingAgent)
        expectMsg[ActivityStartEvent]() // Home activity starts (end of tour 1)
      }

      if (failReturnRoute) {
        // Release behavior at home is covered by the other cases
      } else if (expectRelease) {
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

  private def createFreightPersonWithDepotTours(
    personId: Id[Person],
    truckId: Id[BeamVehicle]
  ): Person = {
    val person = PopulationUtils.getFactory.createPerson(personId)
    val attributesOfIndividual = AttributesOfIndividual(
      HouseholdAttributes("1", 200, 1, 400, 500),
      None,
      true,
      Vector(BeamMode.CAR, BeamMode.WALK),
      Seq.empty,
      valueOfTime = 10000000.0,
      Some(42),
      Some(1234)
    )
    person.getCustomAttributes.put("beam-attributes", attributesOfIndividual)
    PopulationUtils.putPersonAttribute(person, "vehicle", truckId.toString)

    val plan = PopulationUtils.getFactory.createPlan()

    def addTourLeg(tourId: String): Unit = {
      val leg = PopulationUtils.createLeg(BeamMode.CAR.matsimMode)
      leg.getAttributes.putAttribute("tour_id", tourId)
      leg.getAttributes.putAttribute("tour_mode", BeamTourMode.FREIGHT_TOUR.value)
      leg.getAttributes.putAttribute("tour_vehicle", truckId.toString)
      plan.addLeg(leg)
    }

    val depotLocation = homeLocation
    val delivery1Location = workLocation
    val delivery2Location = new Coord(167138.4, 1117.0)

    val depotActivity = PopulationUtils.createActivityFromLinkId("depot", Id.createLinkId(1))
    depotActivity.setEndTime(28800)
    depotActivity.setCoord(depotLocation)
    plan.addActivity(depotActivity)

    addTourLeg("tour-1")

    val delivery1Activity = PopulationUtils.createActivityFromLinkId("delivery", Id.createLinkId(2))
    delivery1Activity.setEndTime(43200)
    delivery1Activity.setCoord(delivery1Location)
    plan.addActivity(delivery1Activity)

    addTourLeg("tour-1")

    val depotActivity2 = PopulationUtils.createActivityFromLinkId("depot", Id.createLinkId(1))
    depotActivity2.setEndTime(48600)
    depotActivity2.setCoord(depotLocation)
    plan.addActivity(depotActivity2)

    addTourLeg("tour-2")

    val delivery2Activity = PopulationUtils.createActivityFromLinkId("delivery", Id.createLinkId(3))
    delivery2Activity.setEndTime(61200)
    delivery2Activity.setCoord(delivery2Location)
    plan.addActivity(delivery2Activity)

    addTourLeg("tour-2")

    val depotActivity3 = PopulationUtils.createActivityFromLinkId("depot", Id.createLinkId(1))
    depotActivity3.setCoord(depotLocation)
    depotActivity3.setEndTime(65200)
    plan.addActivity(depotActivity3)

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

    val leg3 =
      PopulationUtils.createLeg(secondaryTourTripMode.orElse(primaryTourTripMode).map(_.matsimMode).getOrElse(""))
    leg3.getAttributes.putAttribute("tour_id", "101")

    secondaryTourMode.orElse(primaryTourMode).map { mode =>
      leg3.getAttributes.putAttribute("tour_mode", mode.value)
    }
    secondaryTourVehicle.orElse(primaryTourVehicle).map { veh =>
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
    secondaryTourTripMode: Option[BeamMode] = None,
    secondaryTourVehicle: Option[Id[BeamVehicle]] = None,
    extraHouseholdVehicles: Seq[BeamVehicle] = Seq.empty,
    isParentCar: Boolean = true,
    householdHasAvailableCar: Boolean = true,
    vehicleManagerProbe: Option[TestProbe] = None,
    onWorkArrival: (PersonAgent, BeamVehicle) => Unit = (_, _) => ()
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

    val allVehicles =
      ((if (householdHasAvailableCar) Seq(beamVehicle1) else Seq.empty) ++ extraHouseholdVehicles)
        .map(v => v.id -> v)
        .toMap

    val household = householdsFactory.createHousehold(Id.create(s"hh-$personIdStr", classOf[Household]))
    household.setIncome(new IncomeImpl(50000, Income.IncomePeriod.year))
    val population = PopulationUtils.createPopulation(ConfigUtils.createConfig())

    val person: Person = createTestPersonWithSubtour(
      pId,
      primaryTourMode = primaryTourMode,
      primaryTourTripMode = if (isParentCar) None else Some(BeamMode.WALK),
      primaryTourVehicle = primaryTourVehicle.orElse(if (isParentCar) Some(car1Id) else None),
      secondaryTourMode = secondaryTourMode,
      secondaryTourTripMode = secondaryTourTripMode,
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
          parkingManager,
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
    case class WorkInterventionTrigger(tick: Int) extends Trigger
    try {
      scheduler ! ScheduleTrigger(WorkInterventionTrigger(35000), self)
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
      val workTriggerMsg = fishForMessage(10.seconds) {
        case TriggerWithId(WorkInterventionTrigger(35000), _) => true
        case _                                                => false
      }.asInstanceOf[TriggerWithId]
      onWorkArrival(getUnderlyingPersonAgent(householdActor, pId.toString), activeParentCar)
      scheduler ! CompletionNotice(workTriggerMsg.triggerId, Vector())
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

  private def getUnderlyingPersonAgent(householdActor: ActorRef, personIdStr: String): PersonAgent = {
    val household = householdActor.asInstanceOf[TestActorRef[HouseholdActor]].underlyingActor
    val childRef = household.context.child(personIdStr).get
    val cellMethod = childRef.getClass.getMethod("underlying")
    cellMethod.setAccessible(true)
    val cell = cellMethod.invoke(childRef)
    val actorMethod = cell.getClass.getMethod("actor")
    actorMethod.setAccessible(true)
    actorMethod.invoke(cell).asInstanceOf[PersonAgent]
  }

  private def getPersonVehicles(personAgent: PersonAgent): mutable.Map[Id[BeamVehicle], VehicleOrToken] = {
    def findField(clazz: Class[_]): java.lang.reflect.Field = {
      if (clazz == null) throw new NoSuchFieldException("beamVehicles")
      clazz.getDeclaredFields.find(f => f.getName == "beamVehicles" || f.getName.endsWith("$$beamVehicles")) match {
        case Some(f) => f
        case None    => findField(clazz.getSuperclass)
      }
    }
    val field = findField(personAgent.getClass)
    field.setAccessible(true)
    field.get(personAgent).asInstanceOf[mutable.Map[Id[BeamVehicle], VehicleOrToken]]
  }

  private def getBasePersonData(personAgent: PersonAgent): PersonAgent.BasePersonData = {
    personAgent.stateData match {
      case d: ChoosesMode.ChoosesModeData => d.personData
      case d: PersonAgent.BasePersonData  => d
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

    it("should adopt emergency car on subtour when parent car is missing and update parent strategy (agent-level)") {
      val vehicleType = beamScenario.vehicleTypes(Id.create("beamVilleCar", classOf[BeamVehicleType]))
      val emergencyCar = new BeamVehicle(
        Id.createVehicleId("adoption-agent-case-emergency-car"),
        new Powertrain(0.0),
        vehicleType
      )

      val (subtourReq, _, scheduler, householdActor, parkingManager) = setupSubtourScenario(
        personIdStr = "adoption-agent-case",
        primaryTourMode = Some(CAR_BASED),
        isParentCar = true,
        onWorkArrival = (personAgent: PersonAgent, parentCar: BeamVehicle) => {
          // Parent car becomes missing at work, and emergency car arrives
          val vehicles = getPersonVehicles(personAgent)
          vehicles.remove(parentCar.id)
          vehicles.put(emergencyCar.id, ActualVehicle(emergencyCar))
        }
      )
      try {
        assert(
          subtourReq.streetVehicles.exists(_.id == emergencyCar.id),
          s"Subtour routing request should include adopted emergency car ${emergencyCar.id}"
        )

        val personAgent = getUnderlyingPersonAgent(householdActor, "adoption-agent-case")
        val parentTour = personAgent._experiencedBeamPlan.tours.find(_.tourId == 100).get
        val parentStrat = personAgent._experiencedBeamPlan.getStrategy[TourModeChoiceStrategy](parentTour)
        assert(
          parentStrat.tourVehicle.contains(emergencyCar.id),
          s"Parent tour strategy should be updated to name adopted vehicle ${emergencyCar.id}"
        )
      } finally {
        killSchedulerAndDrain(scheduler, householdActor, parkingManager)
      }
    }

    it("should return effective parent tour vehicle only for car- and bike-based parent tours") {
      val carId = Id.createVehicleId("parent-car")
      val bikeId = Id.createVehicleId("parent-bike")

      assert(
        ChoosesMode.effectiveParentTourVehicle(Some(TourModeChoiceStrategy(Some(CAR_BASED), Some(carId)))) === Some(
          carId
        )
      )
      assert(
        ChoosesMode.effectiveParentTourVehicle(Some(TourModeChoiceStrategy(Some(BIKE_BASED), Some(bikeId)))) === Some(
          bikeId
        )
      )
      assert(
        ChoosesMode.effectiveParentTourVehicle(Some(TourModeChoiceStrategy(Some(WALK_BASED), Some(carId)))).isEmpty,
        "Walk-based parent tour with egress car must NOT pass vehicle to subtour"
      )
      assert(ChoosesMode.effectiveParentTourVehicle(None).isEmpty)
    }

    it("should allow emergency vehicles on subtour under walk-based parent while rejecting other vehicles") {
      val vehicleType = beamScenario.vehicleTypes(Id.create("beamVilleCar", classOf[BeamVehicleType]))
      val emergencyCar = new BeamVehicle(Id.createVehicleId("87852-emergency-1"), new Powertrain(0.0), vehicleType)
      val ordinaryCar = new BeamVehicle(Id.createVehicleId("ordinary-car"), new Powertrain(0.0), vehicleType)

      // On a subtour of a walk-based parent tour, effectiveParentTourVehicle is None:
      assert(
        ChoosesMode.isVehicleAllowed(emergencyCar, parentTourVehicleId = None, onSubTour = true),
        "Emergency vehicle must be allowed on subtour of walk-based parent"
      )
      assert(
        !ChoosesMode.isVehicleAllowed(ordinaryCar, parentTourVehicleId = None, onSubTour = true),
        "Ordinary non-parent vehicle must be rejected on subtour"
      )
    }

    it(
      "should allow emergency vehicle across entire subtour under walk-based parent, create only one, release it when subtour ends, and retain parent egress car"
    ) {
      val pIdStr = "subtour-emg-cycle"
      val pId = Id.createPersonId(pIdStr)
      val egressCarId = Id.createVehicleId(s"car1-$pIdStr")
      val vehicleProbe = TestProbe()

      // Primary tour is WALK_BASED with egressCar as tour vehicle;
      // Secondary tour is CAR_BASED without a car.
      val (subtourReq1, _, scheduler, householdActor, parkingManager) = setupSubtourScenario(
        personIdStr = pIdStr,
        primaryTourMode = Some(WALK_BASED),
        primaryTourVehicle = Some(egressCarId),
        secondaryTourMode = Some(CAR_BASED),
        secondaryTourTripMode = Some(BeamMode.CAR),
        secondaryTourVehicle = None,
        extraHouseholdVehicles = Seq.empty,
        isParentCar = false,
        householdHasAvailableCar = false,
        vehicleManagerProbe = Some(vehicleProbe)
      )
      try {
        val linkIds = Array[Int](228, 206, 180, 178, 184, 102)
        val personVehicle = subtourReq1.streetVehicles.find(_.mode == WALK).get

        // 1. First subtour trip (work -> other):
        // Fleet manager should have generated an emergency car
        val emergencyVehOpt = subtourReq1.streetVehicles.find(v => BeamVehicle.isEmergencyVehicle(v.id))
        assert(
          emergencyVehOpt.isDefined,
          s"Emergency vehicle must be present in subtour routing request: ${subtourReq1.streetVehicles}"
        )
        val emergencyVeh = emergencyVehOpt.get
        assert(emergencyVeh.id.toString.contains("-emergency-"))
        assert(!subtourReq1.streetVehicles.exists(_.id == egressCarId), "Egress car must not be offered at work")

        // Intercept manager on emergency vehicle so we can observe release
        val personAgent = getUnderlyingPersonAgent(householdActor, pIdStr)
        val emergencyBeamVeh = getPersonVehicles(personAgent)(emergencyVeh.id).asInstanceOf[ActualVehicle].vehicle
        emergencyBeamVeh.setManager(Some(vehicleProbe.ref))

        // Respond to first subtour leg with CAR trip using emergency vehicle
        personAgent.self ! RoutingResponse(
          itineraries = Vector(
            EmbodiedBeamTrip(
              legs = Vector(
                EmbodiedBeamLeg.dummyLegAt(
                  subtourReq1.departureTime,
                  personVehicle.id,
                  false,
                  services.geo.utm2Wgs(subtourReq1.originUTM),
                  WALK,
                  personVehicle.vehicleTypeId
                ),
                createEmbodiedBeamLeg(subtourReq1, emergencyVeh, linkIds, 50d),
                EmbodiedBeamLeg.dummyLegAt(
                  subtourReq1.departureTime + 250,
                  personVehicle.id,
                  true,
                  services.geo.utm2Wgs(subtourReq1.destinationUTM),
                  WALK,
                  personVehicle.vehicleTypeId
                )
              )
            )
          ),
          requestId = subtourReq1.requestId,
          request = Some(subtourReq1),
          isEmbodyWithCurrentTravelTime = false,
          triggerId = subtourReq1.triggerId
        )

        val mce1 = fishForMessage(30.seconds) {
          case ev: ModeChoiceEvent if ev.personId == pId => true
          case _                                         => false
        }.asInstanceOf[ModeChoiceEvent]
        assert(mce1.mode === "car", "Emergency car must be accepted on first subtour trip")
        assert(mce1.chosenTrip.vehiclesInTrip.contains(emergencyVeh.id))

        fishForMessage(30.seconds) {
          case ev: ActivityEndEvent if ev.getPersonId == pId => true
          case _                                             => false
        }
        val parkingReq1 = fishForMessage(30.seconds) {
          case req: RoutingRequest if req.personId.contains(pId) => true
          case _                                                 => false
        }.asInstanceOf[RoutingRequest]
        val parkingAgent1 = lastSender
        parkingAgent1 ! RoutingResponse(
          itineraries = Vector(
            EmbodiedBeamTrip(
              legs = Vector(
                EmbodiedBeamLeg(
                  beamLeg = BeamLeg(
                    startTime = parkingReq1.departureTime,
                    mode = BeamMode.CAR,
                    duration = 50,
                    travelPath = BeamPath(
                      linkIds = Array(142, 60, 58, 62, 80),
                      linkTravelTime = Array(50, 50, 50, 50, 50),
                      transitStops = None,
                      startPoint = SpaceTime(services.geo.utm2Wgs(parkingReq1.originUTM), parkingReq1.departureTime),
                      endPoint =
                        SpaceTime(services.geo.utm2Wgs(parkingReq1.destinationUTM), parkingReq1.departureTime + 200),
                      distanceInM = 1000d
                    )
                  ),
                  beamVehicleId = emergencyVeh.id,
                  emergencyBeamVeh.beamVehicleType.id,
                  asDriver = true,
                  cost = 0.0,
                  unbecomeDriverOnCompletion = true
                )
              )
            )
          ),
          requestId = parkingReq1.requestId,
          request = Some(parkingReq1),
          isEmbodyWithCurrentTravelTime = false,
          triggerId = parkingReq1.triggerId
        )

        val walkFromParkingReq1 = fishForMessage(30.seconds) {
          case req: RoutingRequest if req.personId.contains(pId) => true
          case _                                                 => false
        }.asInstanceOf[RoutingRequest]
        val walkAgent1 = lastSender
        walkAgent1 ! RoutingResponse(
          itineraries = Vector(
            EmbodiedBeamTrip(
              legs = Vector(
                EmbodiedBeamLeg(
                  beamLeg = BeamLeg(
                    startTime = walkFromParkingReq1.departureTime,
                    mode = BeamMode.WALK,
                    duration = 50,
                    travelPath = BeamPath(
                      linkIds = Array(80, 101),
                      linkTravelTime = Array(50, 50),
                      transitStops = None,
                      startPoint = SpaceTime(
                        services.geo.utm2Wgs(walkFromParkingReq1.originUTM),
                        walkFromParkingReq1.departureTime
                      ),
                      endPoint = SpaceTime(
                        services.geo.utm2Wgs(walkFromParkingReq1.destinationUTM),
                        walkFromParkingReq1.departureTime + 50
                      ),
                      distanceInM = 100d
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
          requestId = walkFromParkingReq1.requestId,
          request = Some(walkFromParkingReq1),
          isEmbodyWithCurrentTravelTime = false,
          triggerId = walkFromParkingReq1.triggerId
        )

        fishForMessage(30.seconds) {
          case ev: ActivityStartEvent if ev.getPersonId == pId => true
          case _                                               => false
        }

        // At intermediate activity (other/atwork), verify currentTourPersonalVehicle is still emergency-1
        assert(
          getBasePersonData(personAgent).currentTourPersonalVehicle.contains(emergencyVeh.id),
          "Emergency car must be retained in currentTourPersonalVehicle at intermediate subtour activity"
        )

        // 2. Second subtour trip (other -> work):
        val subtourReq2 = fishForMessage(30.seconds) {
          case req: RoutingRequest if req.personId.contains(pId) => true
          case _                                                 => false
        }.asInstanceOf[RoutingRequest]
        val subtourAgent2 = lastSender

        // Assert: only one emergency car was created across both trips!
        assert(
          subtourReq2.streetVehicles.exists(_.id == emergencyVeh.id),
          s"Emergency car ${emergencyVeh.id} must be offered on second subtour trip"
        )
        assert(
          !subtourReq2.streetVehicles.exists(v => BeamVehicle.isEmergencyVehicle(v.id) && v.id != emergencyVeh.id),
          s"No second emergency car should ever be created: ${subtourReq2.streetVehicles}"
        )

        // Reset probe manager on emergency car before return trip
        emergencyBeamVeh.setManager(Some(vehicleProbe.ref))

        personAgent.self ! RoutingResponse(
          itineraries = Vector(
            EmbodiedBeamTrip(
              legs = Vector(
                EmbodiedBeamLeg.dummyLegAt(
                  subtourReq2.departureTime,
                  personVehicle.id,
                  false,
                  services.geo.utm2Wgs(subtourReq2.originUTM),
                  WALK,
                  personVehicle.vehicleTypeId
                ),
                createEmbodiedBeamLeg(subtourReq2, emergencyVeh, linkIds, 50d),
                EmbodiedBeamLeg.dummyLegAt(
                  subtourReq2.departureTime + 250,
                  personVehicle.id,
                  true,
                  services.geo.utm2Wgs(subtourReq2.destinationUTM),
                  WALK,
                  personVehicle.vehicleTypeId
                )
              )
            )
          ),
          requestId = subtourReq2.requestId,
          request = Some(subtourReq2),
          isEmbodyWithCurrentTravelTime = false,
          triggerId = subtourReq2.triggerId
        )

        val mce2 = fishForMessage(30.seconds) {
          case ev: ModeChoiceEvent if ev.personId == pId => true
          case _                                         => false
        }.asInstanceOf[ModeChoiceEvent]
        assert(mce2.mode === "car", "Emergency car must be accepted on second subtour trip")
        assert(mce2.chosenTrip.vehiclesInTrip.contains(emergencyVeh.id))

        fishForMessage(30.seconds) {
          case ev: ActivityEndEvent if ev.getPersonId == pId => true
          case _                                             => false
        }
        val parkingReq2 = fishForMessage(30.seconds) {
          case req: RoutingRequest if req.personId.contains(pId) => true
          case _                                                 => false
        }.asInstanceOf[RoutingRequest]
        val parkingAgent2 = lastSender
        parkingAgent2 ! RoutingResponse(
          itineraries = Vector(
            EmbodiedBeamTrip(
              legs = Vector(
                EmbodiedBeamLeg(
                  beamLeg = BeamLeg(
                    startTime = parkingReq2.departureTime,
                    mode = BeamMode.CAR,
                    duration = 50,
                    travelPath = BeamPath(
                      linkIds = Array(142, 60, 58, 62, 80),
                      linkTravelTime = Array(50, 50, 50, 50, 50),
                      transitStops = None,
                      startPoint = SpaceTime(services.geo.utm2Wgs(parkingReq2.originUTM), parkingReq2.departureTime),
                      endPoint =
                        SpaceTime(services.geo.utm2Wgs(parkingReq2.destinationUTM), parkingReq2.departureTime + 200),
                      distanceInM = 1000d
                    )
                  ),
                  beamVehicleId = emergencyVeh.id,
                  emergencyBeamVeh.beamVehicleType.id,
                  asDriver = true,
                  cost = 0.0,
                  unbecomeDriverOnCompletion = true
                )
              )
            )
          ),
          requestId = parkingReq2.requestId,
          request = Some(parkingReq2),
          isEmbodyWithCurrentTravelTime = false,
          triggerId = parkingReq2.triggerId
        )

        val walkFromParkingReq2 = fishForMessage(30.seconds) {
          case req: RoutingRequest if req.personId.contains(pId) => true
          case _                                                 => false
        }.asInstanceOf[RoutingRequest]
        val walkAgent2 = lastSender
        walkAgent2 ! RoutingResponse(
          itineraries = Vector(
            EmbodiedBeamTrip(
              legs = Vector(
                EmbodiedBeamLeg(
                  beamLeg = BeamLeg(
                    startTime = walkFromParkingReq2.departureTime,
                    mode = BeamMode.WALK,
                    duration = 50,
                    travelPath = BeamPath(
                      linkIds = Array(80, 101),
                      linkTravelTime = Array(50, 50),
                      transitStops = None,
                      startPoint = SpaceTime(
                        services.geo.utm2Wgs(walkFromParkingReq2.originUTM),
                        walkFromParkingReq2.departureTime
                      ),
                      endPoint = SpaceTime(
                        services.geo.utm2Wgs(walkFromParkingReq2.destinationUTM),
                        walkFromParkingReq2.departureTime + 50
                      ),
                      distanceInM = 100d
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
          requestId = walkFromParkingReq2.requestId,
          request = Some(walkFromParkingReq2),
          isEmbodyWithCurrentTravelTime = false,
          triggerId = walkFromParkingReq2.triggerId
        )

        fishForMessage(30.seconds) {
          case ev: ActivityStartEvent if ev.getPersonId == pId => true
          case _                                               => false
        }

        // 3. Subtour has ended (arrival back at Work):
        // Assert: emergency car is released to its manager!
        vehicleProbe.fishForMessage(3.seconds) {
          case ReleaseVehicle(veh, _) => veh.id == emergencyVeh.id
          case _                      => false
        }
        assert(
          !getPersonVehicles(personAgent).contains(emergencyVeh.id),
          "Emergency car should be removed from person's vehicles after subtour ends"
        )

        // 4. Assert: egress car is still the parent tour's vehicle afterwards!
        val parentTour = personAgent._experiencedBeamPlan.tours.find(_.tourId == 100).get
        val parentStrat = personAgent._experiencedBeamPlan.getStrategy[TourModeChoiceStrategy](parentTour)
        assert(
          parentStrat.tourVehicle.contains(egressCarId),
          s"Parent tour strategy must retain egress car $egressCarId, got ${parentStrat.tourVehicle}"
        )
      } finally {
        killSchedulerAndDrain(scheduler, householdActor, parkingManager)
      }
    }

    it(
      "should create and use emergency car on subtour under walk-based parent even when household has another free car at home"
    ) {
      val pIdStr = "subtour-hhcar-free"
      val pId = Id.createPersonId(pIdStr)
      val vehicleProbe = TestProbe()

      // Primary tour is WALK_BASED with no tour vehicle;
      // Secondary tour is CAR_BASED without a car.
      // Household has a free car (householdHasAvailableCar = true) parked at home.
      val (subtourReq1, _, scheduler, householdActor, parkingManager) = setupSubtourScenario(
        personIdStr = pIdStr,
        primaryTourMode = Some(WALK_BASED),
        primaryTourVehicle = None,
        secondaryTourMode = Some(CAR_BASED),
        secondaryTourTripMode = Some(BeamMode.CAR),
        secondaryTourVehicle = None,
        extraHouseholdVehicles = Seq.empty,
        isParentCar = false,
        householdHasAvailableCar = true,
        vehicleManagerProbe = Some(vehicleProbe)
      )
      try {
        val linkIds = Array[Int](228, 206, 180, 178, 184, 102)
        val personVehicle = subtourReq1.streetVehicles.find(_.mode == WALK).get
        val homeCarId = Id.createVehicleId(s"car1-$pIdStr")

        // 1. First subtour trip (work -> other):
        // Fleet manager should have generated an emergency car, NOT offered the car parked at home
        assert(
          !subtourReq1.streetVehicles.exists(_.id == homeCarId),
          s"Home car $homeCarId must not be offered to person at work on subtour: ${subtourReq1.streetVehicles}"
        )
        val emergencyVehOpt = subtourReq1.streetVehicles.find(v => BeamVehicle.isEmergencyVehicle(v.id))
        assert(
          emergencyVehOpt.isDefined,
          s"Emergency vehicle must be generated despite free car at home: ${subtourReq1.streetVehicles}"
        )
        val emergencyVeh = emergencyVehOpt.get
        assert(emergencyVeh.id.toString.contains("-emergency-"))

        val personAgent = getUnderlyingPersonAgent(householdActor, pIdStr)
        val emergencyBeamVeh = getPersonVehicles(personAgent)(emergencyVeh.id).asInstanceOf[ActualVehicle].vehicle
        emergencyBeamVeh.setManager(Some(vehicleProbe.ref))

        // Respond to first subtour leg with CAR trip using emergency vehicle
        personAgent.self ! RoutingResponse(
          itineraries = Vector(
            EmbodiedBeamTrip(
              legs = Vector(
                EmbodiedBeamLeg.dummyLegAt(
                  subtourReq1.departureTime,
                  personVehicle.id,
                  false,
                  services.geo.utm2Wgs(subtourReq1.originUTM),
                  WALK,
                  personVehicle.vehicleTypeId
                ),
                createEmbodiedBeamLeg(subtourReq1, emergencyVeh, linkIds, 50d),
                EmbodiedBeamLeg.dummyLegAt(
                  subtourReq1.departureTime + 250,
                  personVehicle.id,
                  true,
                  services.geo.utm2Wgs(subtourReq1.destinationUTM),
                  WALK,
                  personVehicle.vehicleTypeId
                )
              )
            )
          ),
          requestId = subtourReq1.requestId,
          request = Some(subtourReq1),
          isEmbodyWithCurrentTravelTime = false,
          triggerId = subtourReq1.triggerId
        )

        val mce1 = fishForMessage(30.seconds) {
          case ev: ModeChoiceEvent if ev.personId == pId => true
          case _                                         => false
        }.asInstanceOf[ModeChoiceEvent]
        assert(mce1.mode === "car", "Emergency car must be accepted on first subtour trip")
        assert(mce1.chosenTrip.vehiclesInTrip.contains(emergencyVeh.id))

        fishForMessage(30.seconds) {
          case ev: ActivityEndEvent if ev.getPersonId == pId => true
          case _                                             => false
        }
        val parkingReq1 = fishForMessage(30.seconds) {
          case req: RoutingRequest if req.personId.contains(pId) => true
          case _                                                 => false
        }.asInstanceOf[RoutingRequest]
        val parkingAgent1 = lastSender
        parkingAgent1 ! RoutingResponse(
          itineraries = Vector(
            EmbodiedBeamTrip(
              legs = Vector(
                EmbodiedBeamLeg(
                  beamLeg = BeamLeg(
                    startTime = parkingReq1.departureTime,
                    mode = BeamMode.CAR,
                    duration = 50,
                    travelPath = BeamPath(
                      linkIds = Array(142, 60, 58, 62, 80),
                      linkTravelTime = Array(50, 50, 50, 50, 50),
                      transitStops = None,
                      startPoint = SpaceTime(services.geo.utm2Wgs(parkingReq1.originUTM), parkingReq1.departureTime),
                      endPoint =
                        SpaceTime(services.geo.utm2Wgs(parkingReq1.destinationUTM), parkingReq1.departureTime + 200),
                      distanceInM = 1000d
                    )
                  ),
                  beamVehicleId = emergencyVeh.id,
                  emergencyBeamVeh.beamVehicleType.id,
                  asDriver = true,
                  cost = 0.0,
                  unbecomeDriverOnCompletion = true
                )
              )
            )
          ),
          requestId = parkingReq1.requestId,
          request = Some(parkingReq1),
          isEmbodyWithCurrentTravelTime = false,
          triggerId = parkingReq1.triggerId
        )

        val walkFromParkingReq1 = fishForMessage(30.seconds) {
          case req: RoutingRequest if req.personId.contains(pId) => true
          case _                                                 => false
        }.asInstanceOf[RoutingRequest]
        val walkAgent1 = lastSender
        walkAgent1 ! RoutingResponse(
          itineraries = Vector(
            EmbodiedBeamTrip(
              legs = Vector(
                EmbodiedBeamLeg(
                  beamLeg = BeamLeg(
                    startTime = walkFromParkingReq1.departureTime,
                    mode = BeamMode.WALK,
                    duration = 50,
                    travelPath = BeamPath(
                      linkIds = Array(80, 101),
                      linkTravelTime = Array(50, 50),
                      transitStops = None,
                      startPoint = SpaceTime(
                        services.geo.utm2Wgs(walkFromParkingReq1.originUTM),
                        walkFromParkingReq1.departureTime
                      ),
                      endPoint = SpaceTime(
                        services.geo.utm2Wgs(walkFromParkingReq1.destinationUTM),
                        walkFromParkingReq1.departureTime + 50
                      ),
                      distanceInM = 100d
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
          requestId = walkFromParkingReq1.requestId,
          request = Some(walkFromParkingReq1),
          isEmbodyWithCurrentTravelTime = false,
          triggerId = walkFromParkingReq1.triggerId
        )

        fishForMessage(30.seconds) {
          case ev: ActivityStartEvent if ev.getPersonId == pId => true
          case _                                               => false
        }

        // Verify currentTourPersonalVehicle is emergency car at intermediate subtour activity
        assert(
          getBasePersonData(personAgent).currentTourPersonalVehicle.contains(emergencyVeh.id),
          "Emergency car must be retained in currentTourPersonalVehicle at intermediate subtour activity"
        )

        // 2. Second subtour trip (other -> work):
        val subtourReq2 = fishForMessage(30.seconds) {
          case req: RoutingRequest if req.personId.contains(pId) => true
          case _                                                 => false
        }.asInstanceOf[RoutingRequest]

        assert(
          subtourReq2.streetVehicles.exists(_.id == emergencyVeh.id),
          s"Emergency car ${emergencyVeh.id} must be offered on second subtour trip"
        )
        assert(
          !subtourReq2.streetVehicles.exists(_.id == homeCarId),
          s"Home car $homeCarId must not be offered on second subtour trip either"
        )

        emergencyBeamVeh.setManager(Some(vehicleProbe.ref))

        personAgent.self ! RoutingResponse(
          itineraries = Vector(
            EmbodiedBeamTrip(
              legs = Vector(
                EmbodiedBeamLeg.dummyLegAt(
                  subtourReq2.departureTime,
                  personVehicle.id,
                  false,
                  services.geo.utm2Wgs(subtourReq2.originUTM),
                  WALK,
                  personVehicle.vehicleTypeId
                ),
                createEmbodiedBeamLeg(subtourReq2, emergencyVeh, linkIds, 50d),
                EmbodiedBeamLeg.dummyLegAt(
                  subtourReq2.departureTime + 250,
                  personVehicle.id,
                  true,
                  services.geo.utm2Wgs(subtourReq2.destinationUTM),
                  WALK,
                  personVehicle.vehicleTypeId
                )
              )
            )
          ),
          requestId = subtourReq2.requestId,
          request = Some(subtourReq2),
          isEmbodyWithCurrentTravelTime = false,
          triggerId = subtourReq2.triggerId
        )

        val mce2 = fishForMessage(30.seconds) {
          case ev: ModeChoiceEvent if ev.personId == pId => true
          case _                                         => false
        }.asInstanceOf[ModeChoiceEvent]
        assert(mce2.mode === "car", "Emergency car must be accepted on second subtour trip")
        assert(mce2.chosenTrip.vehiclesInTrip.contains(emergencyVeh.id))

        fishForMessage(30.seconds) {
          case ev: ActivityEndEvent if ev.getPersonId == pId => true
          case _                                             => false
        }
        val parkingReq2 = fishForMessage(30.seconds) {
          case req: RoutingRequest if req.personId.contains(pId) => true
          case _                                                 => false
        }.asInstanceOf[RoutingRequest]
        val parkingAgent2 = lastSender
        parkingAgent2 ! RoutingResponse(
          itineraries = Vector(
            EmbodiedBeamTrip(
              legs = Vector(
                EmbodiedBeamLeg(
                  beamLeg = BeamLeg(
                    startTime = parkingReq2.departureTime,
                    mode = BeamMode.CAR,
                    duration = 50,
                    travelPath = BeamPath(
                      linkIds = Array(142, 60, 58, 62, 80),
                      linkTravelTime = Array(50, 50, 50, 50, 50),
                      transitStops = None,
                      startPoint = SpaceTime(services.geo.utm2Wgs(parkingReq2.originUTM), parkingReq2.departureTime),
                      endPoint =
                        SpaceTime(services.geo.utm2Wgs(parkingReq2.destinationUTM), parkingReq2.departureTime + 200),
                      distanceInM = 1000d
                    )
                  ),
                  beamVehicleId = emergencyVeh.id,
                  emergencyBeamVeh.beamVehicleType.id,
                  asDriver = true,
                  cost = 0.0,
                  unbecomeDriverOnCompletion = true
                )
              )
            )
          ),
          requestId = parkingReq2.requestId,
          request = Some(parkingReq2),
          isEmbodyWithCurrentTravelTime = false,
          triggerId = parkingReq2.triggerId
        )

        val walkFromParkingReq2 = fishForMessage(30.seconds) {
          case req: RoutingRequest if req.personId.contains(pId) => true
          case _                                                 => false
        }.asInstanceOf[RoutingRequest]
        val walkAgent2 = lastSender
        walkAgent2 ! RoutingResponse(
          itineraries = Vector(
            EmbodiedBeamTrip(
              legs = Vector(
                EmbodiedBeamLeg(
                  beamLeg = BeamLeg(
                    startTime = walkFromParkingReq2.departureTime,
                    mode = BeamMode.WALK,
                    duration = 50,
                    travelPath = BeamPath(
                      linkIds = Array(80, 101),
                      linkTravelTime = Array(50, 50),
                      transitStops = None,
                      startPoint = SpaceTime(
                        services.geo.utm2Wgs(walkFromParkingReq2.originUTM),
                        walkFromParkingReq2.departureTime
                      ),
                      endPoint = SpaceTime(
                        services.geo.utm2Wgs(walkFromParkingReq2.destinationUTM),
                        walkFromParkingReq2.departureTime + 50
                      ),
                      distanceInM = 100d
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
          requestId = walkFromParkingReq2.requestId,
          request = Some(walkFromParkingReq2),
          isEmbodyWithCurrentTravelTime = false,
          triggerId = walkFromParkingReq2.triggerId
        )

        fishForMessage(30.seconds) {
          case ev: ActivityStartEvent if ev.getPersonId == pId => true
          case _                                               => false
        }

        // 3. Subtour has ended (arrival back at Work):
        // Assert: emergency car is released to its manager!
        vehicleProbe.fishForMessage(3.seconds) {
          case ReleaseVehicle(veh, _) => veh.id == emergencyVeh.id
          case _                      => false
        }
        assert(
          !getPersonVehicles(personAgent).contains(emergencyVeh.id),
          "Emergency car should be removed from person's vehicles after subtour ends"
        )
      } finally {
        killSchedulerAndDrain(scheduler, householdActor, parkingManager)
      }
    }

    it("should not save shared bike or scooter chosen on walk tour as tour vehicle") {
      val sharedBikeId = Id.createVehicleId("sharedVehicle-bike-1")
      val sanitized =
        ChoosesMode.sanitizeTourVehicleId(Some(sharedBikeId), parentTourVehicleId = None, onSubTour = false)
      assert(sanitized.isEmpty, "Shared vehicle must not be stored as tour vehicle")

      val sharedTeleportId = Id.createVehicleId(s"${BeamVehicle.idPrefixSharedTeleportationVehicle}-bike-2")
      val sanitizedTeleport =
        ChoosesMode.sanitizeTourVehicleId(Some(sharedTeleportId), parentTourVehicleId = None, onSubTour = false)
      assert(sanitizedTeleport.isEmpty, "Shared teleportation vehicle must not be stored as tour vehicle")
    }
  }

  describe("Emergency vehicle cleanup (A3)") {
    it("should drop and release unreferenced emergency vehicle on subtour to its manager") {
      val vehicleType = beamScenario.vehicleTypes(Id.create("beamVilleCar", classOf[BeamVehicleType]))
      val pIdStr = "a3-cleanup-case"
      val emergencyCar =
        new BeamVehicle(Id.createVehicleId(s"$pIdStr-emergency-car"), new Powertrain(0.0), vehicleType)
      val vehicleProbe = TestProbe()

      val (subtourReq, _, scheduler, householdActor, parkingManager) = setupSubtourScenario(
        personIdStr = pIdStr,
        primaryTourMode = Some(CAR_BASED),
        primaryTourVehicle = None,
        extraHouseholdVehicles = Seq.empty,
        isParentCar = true,
        vehicleManagerProbe = Some(vehicleProbe),
        onWorkArrival = (personAgent: PersonAgent, _) => {
          emergencyCar.setManager(Some(vehicleProbe.ref))
          getPersonVehicles(personAgent).put(emergencyCar.id, ActualVehicle(emergencyCar))
        }
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

    it("should drop and release unreferenced emergency vehicle on top-level car-based tour with own car") {
      val eventsManager = new EventsManagerImpl()
      eventsManager.addHandler(new BasicEventHandler {
        override def handleEvent(event: Event): Unit = event match {
          case _: ModeChoiceEvent | _: TourModeChoiceEvent | _: ActivityEndEvent | _: ActivityStartEvent |
              _: ReplanningEvent =>
            self ! event
          case _ =>
        }
      })

      val pId = Id.createPersonId("a3-top-level-person")
      val ownCarId: Id[BeamVehicle] = Id.create("ownCar-a3-top-level", classOf[BeamVehicle])
      val emergencyCarId: Id[BeamVehicle] = Id.create(s"${pId}-emergency-top-level", classOf[BeamVehicle])
      val vehicleProbe = TestProbe()
      val vehicleType = beamScenario.vehicleTypes(Id.create("beamVilleCar", classOf[BeamVehicleType]))
      val ownCar = new BeamVehicle(ownCarId, new Powertrain(0.0), vehicleType)
      val emergencyCar = new BeamVehicle(emergencyCarId, new Powertrain(0.0), vehicleType) {
        override def setManager(value: Option[ActorRef]): Unit = {
          super.setManager(Some(vehicleProbe.ref))
        }
      }
      emergencyCar.setManager(Some(vehicleProbe.ref))

      val household = householdsFactory.createHousehold(Id.create("hh-a3-top-level", classOf[Household]))
      val population = PopulationUtils.createPopulation(ConfigUtils.createConfig())
      val person: Person = createTestPersonWithSequentialTours(
        pId,
        primaryTourMode = Some(CAR_BASED),
        primaryTourVehicle = Some(ownCarId),
        householdSize = 1
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

      val allVehicles = Map(ownCar.id -> ownCar)
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
      receiveWhile(500 millis) { case _ => }
      val schedulerProbe = TestProbe()
      case class InjectEmergencyCarTrigger(tick: Int) extends Trigger
      try {
        scheduler ! ScheduleTrigger(InjectEmergencyCarTrigger(1), self)
        scheduler ! ScheduleTrigger(InitializeTrigger(0), householdActor)
        scheduler.tell(StartSchedule(0), schedulerProbe.ref)

        val triggerMsg = fishForMessage(10.seconds) {
          case TriggerWithId(InjectEmergencyCarTrigger(1), _) => true
          case _                                              => false
        }.asInstanceOf[TriggerWithId]

        val personAgent = getUnderlyingPersonAgent(householdActor, pId.toString)
        getPersonVehicles(personAgent).put(emergencyCar.id, ActualVehicle(emergencyCar))
        emergencyCar.setManager(Some(vehicleProbe.ref))
        scheduler ! CompletionNotice(triggerMsg.triggerId, Vector())

        val routingRequest = fishForMessage(30.seconds) {
          case req: RoutingRequest if req.personId.contains(pId) => true
          case _                                                 => false
        }.asInstanceOf[RoutingRequest]

        // Emergency car must be dropped from available street vehicles
        assert(!routingRequest.streetVehicles.exists(_.id == emergencyCarId))
        assert(routingRequest.streetVehicles.exists(_.id == ownCarId))

        // And released to its manager
        vehicleProbe.fishForMessage(3.seconds) {
          case ReleaseVehicle(veh, _) => veh.id == emergencyCarId
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

  describe("Failed route fallback with held vehicle") {
    it("should drive the held vehicle only on car and bike based tours, and never for freight agents") {
      assert(ChoosesMode.ownVehicleFallbackMode("person-1", Some(CAR_BASED)) === Some(CAR))
      assert(ChoosesMode.ownVehicleFallbackMode("person-1", Some(BeamTourMode.BIKE_BASED)) === Some(BIKE))
      assert(ChoosesMode.ownVehicleFallbackMode("person-1", Some(WALK_BASED)).isEmpty)
      assert(ChoosesMode.ownVehicleFallbackMode("person-1", None).isEmpty)
      // Freight agents keep their dedicated freight failure branch
      assert(ChoosesMode.ownVehicleFallbackMode("ft-carrier-1-tour-1", Some(BeamTourMode.FREIGHT_TOUR)).isEmpty)
      assert(ChoosesMode.ownVehicleFallbackMode("ft-carrier-1-tour-1", Some(CAR_BASED)).isEmpty)
    }
  }

  describe("Freight agent depot tours and fallback (agent-level)") {
    it("should keep truck on second depot tour, receive no parent tour, and not release truck on route failure") {
      val eventsManager = new EventsManagerImpl()
      eventsManager.addHandler(new BasicEventHandler {
        override def handleEvent(event: Event): Unit = event match {
          case _: ModeChoiceEvent | _: TourModeChoiceEvent | _: ActivityEndEvent | _: ActivityStartEvent |
              _: ReplanningEvent =>
            self ! event
          case _ =>
        }
      })

      val carrierId = "ft-carrier-1"
      val pId = Id.createPersonId(carrierId)
      val truckId = Id.createVehicleId("ft-truck-1")
      val vehicleType = beamScenario
        .vehicleTypes(Id.create("beamVilleCar", classOf[BeamVehicleType]))
        .copy(vehicleUse = beam.agentsim.agents.vehicles.VehicleUse.Freight)
      val freightManagerId =
        VehicleManager.createOrGetReservedFor(carrierId, Some(VehicleManager.TypeEnum.Freight)).managerId
      val truck = new BeamVehicle(
        truckId,
        new Powertrain(0.0),
        vehicleType,
        vehicleManagerId = new java.util.concurrent.atomic.AtomicReference(freightManagerId)
      )
      val vehicleProbe = TestProbe()
      truck.setManager(Some(vehicleProbe.ref))

      val household = householdsFactory.createHousehold(Id.create(s"$carrierId-hh", classOf[Household]))
      val population = PopulationUtils.createPopulation(ConfigUtils.createConfig())
      val person: Person = createFreightPersonWithDepotTours(pId, truckId)
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
      val schedulerProbe = TestProbe()

      val allVehicles = Map(truck.id -> truck)
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

      val linkIds = Array[Int](228, 206, 180, 178, 184, 102)

      def respondFreightTour(routingRequest: RoutingRequest, targetAgent: ActorRef): Unit = {
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
                createEmbodiedBeamLeg(routingRequest, truck.toStreetVehicle, linkIds, 50d),
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
      }

      def handleFreightParkingAndWalk(parkingRoutingRequest: RoutingRequest, parkingAgent: ActorRef): Unit = {
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
                  beamVehicleId = truck.id,
                  truck.beamVehicleType.id,
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
        scheduler ! ScheduleTrigger(InitializeTrigger(0), householdActor)
        scheduler.tell(StartSchedule(0), schedulerProbe.ref)
        truck.setManager(Some(vehicleProbe.ref))

        // Tour 1 leg 1: depot -> delivery 1
        val req1 = fishForMessage(30.seconds) {
          case req: RoutingRequest if req.personId.contains(pId) => true
          case _                                                 => false
        }.asInstanceOf[RoutingRequest]
        val agent1 = lastSender
        truck.setManager(Some(vehicleProbe.ref))
        assert(req1.streetVehicles.exists(_.id == truckId))
        respondFreightTour(req1, agent1)

        fishForMessage(30.seconds) { case mce: ModeChoiceEvent if mce.personId == pId => true; case _ => false }
        fishForMessage(30.seconds) { case aee: ActivityEndEvent if aee.getPersonId == pId => true; case _ => false }
        val parking1 = fishForMessage(30.seconds) {
          case req: RoutingRequest if req.personId.contains(pId) => true
          case _                                                 => false
        }.asInstanceOf[RoutingRequest]
        handleFreightParkingAndWalk(parking1, lastSender)
        fishForMessage(30.seconds) { case ase: ActivityStartEvent if ase.getPersonId == pId => true; case _ => false }

        // Tour 1 leg 2: delivery 1 -> depot
        val req2 = fishForMessage(30.seconds) {
          case req: RoutingRequest if req.personId.contains(pId) => true
          case _                                                 => false
        }.asInstanceOf[RoutingRequest]
        val agent2 = lastSender
        truck.setManager(Some(vehicleProbe.ref))
        assert(req2.streetVehicles.exists(_.id == truckId))
        respondFreightTour(req2, agent2)

        fishForMessage(30.seconds) { case mce: ModeChoiceEvent if mce.personId == pId => true; case _ => false }
        fishForMessage(30.seconds) { case aee: ActivityEndEvent if aee.getPersonId == pId => true; case _ => false }
        val parking2 = fishForMessage(30.seconds) {
          case req: RoutingRequest if req.personId.contains(pId) => true
          case _                                                 => false
        }.asInstanceOf[RoutingRequest]
        handleFreightParkingAndWalk(parking2, lastSender)
        fishForMessage(30.seconds) { case ase: ActivityStartEvent if ase.getPersonId == pId => true; case _ => false }

        // Depot activity starts (end of tour 1). Truck must NOT be released at depot
        val depotMsgs = vehicleProbe.receiveWhile(300.millis) { case m => m }
        assert(
          !depotMsgs.exists(_.isInstanceOf[ReleaseVehicle]),
          s"Freight truck must not be released at depot: $depotMsgs"
        )

        // Tour 2 leg 1: depot -> delivery 2
        val req3 = fishForMessage(30.seconds) {
          case req: RoutingRequest if req.personId.contains(pId) => true
          case _                                                 => false
        }.asInstanceOf[RoutingRequest]
        val agent3 = lastSender
        truck.setManager(Some(vehicleProbe.ref))

        // Assertion: keeps truck on second tour
        assert(
          req3.streetVehicles.exists(_.id == truckId),
          "Freight agent should keep its truck on the second tour"
        )

        // Assertion: gets no parent-tour behavior
        val personAgent = getUnderlyingPersonAgent(householdActor, pId.toString)
        val tour2 = personAgent._experiencedBeamPlan.tours(1)
        val tour2Strat = personAgent._experiencedBeamPlan.getStrategy[TourModeChoiceStrategy](tour2)
        assert(tour2Strat.tourMode.contains(BeamTourMode.FREIGHT_TOUR))
        val basePersonData = getBasePersonData(personAgent)
        val getParentMethod =
          classOf[PersonAgent].getDeclaredMethod("getParentTourStrategy", classOf[PersonAgent.BasePersonData])
        getParentMethod.setAccessible(true)
        val parentStrategy =
          getParentMethod.invoke(personAgent, basePersonData).asInstanceOf[Option[TourModeChoiceStrategy]]
        assert(parentStrategy.isEmpty, "Freight agent should have no parent tour strategy")

        // Fail routing on Tour 2
        agent3 ! RoutingResponse(
          Vector.empty,
          req3.requestId,
          Some(req3),
          isEmbodyWithCurrentTravelTime = false,
          triggerId = req3.triggerId
        )

        // Assertion: doesn't release truck when route fails, takes expensive trip in truck
        val mce3 = fishForMessage(30.seconds) {
          case mce: ModeChoiceEvent if mce.personId == pId => true
          case _                                           => false
        }.asInstanceOf[ModeChoiceEvent]
        assert(mce3.mode === "car", s"Expected expensive car trip for freight, got ${mce3.mode}")
        assert(mce3.chosenTrip.vehiclesInTrip.contains(truckId), "Freight trip should use the truck")
        val failMsgs = vehicleProbe.receiveWhile(300.millis) { case m => m }
        assert(
          !failMsgs.exists(_.isInstanceOf[ReleaseVehicle]),
          "Freight truck must not be released when route fails"
        )
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
                Array(0, 100), // BeamPath validation requires linkTravelTime.tail.sum to match duration (100)
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
