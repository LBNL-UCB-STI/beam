package beam.router

import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

import akka.actor.{ActorIdentity, ActorRef, ActorSystem, Identify}
import akka.testkit.{ImplicitSender, TestKit}
import beam.agentsim.agents.choice.mode.PtFares
import beam.agentsim.agents.choice.mode.PtFares.FareRule
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.FuelType.FuelType
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.integration.IntegrationSpecCommon
import beam.router.BeamRouter._
import beam.router.Modes.BeamMode.CAR
import beam.router.gtfs.FareCalculator
import beam.router.gtfs.FareCalculator.BeamFareSegment
import beam.router.osm.TollCalculator
import beam.router.r5.DefaultNetworkCoordinator
import beam.sim.common.GeoUtilsImpl
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.population.DefaultPopulationAdjustment
import beam.sim.{BeamHelper, BeamServices, BeamWarmStart}
import beam.utils.TestConfigUtils.testConfig
import beam.utils.{DateUtils, FileUtils, NetworkHelperImpl}
import com.typesafe.config.{Config, ConfigValueFactory}
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Id, Scenario}
import org.matsim.core.config.ConfigUtils
import org.matsim.core.config.groups.TravelTimeCalculatorConfigGroup
import org.matsim.core.controler.AbstractModule
import org.matsim.core.events.EventsManagerImpl
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.matsim.households.Household
import org.matsim.vehicles.Vehicle
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._
import scala.language.postfixOps

class WarmStartRoutingSpec
    extends TestKit(
      ActorSystem(
        "WarmStartRoutingSpec",
        testConfig("test/input/beamville/beam.conf")
          .resolve()
          .withValue("beam.warmStart.enabled", ConfigValueFactory.fromAnyRef(true))
          .withValue(
            "beam.warmStart.path",
            ConfigValueFactory
              .fromAnyRef("test/input/beamville/test-data/")
          )
      )
    )
    with BeamHelper
    with WordSpecLike
    with Matchers
    with ImplicitSender
    with IntegrationSpecCommon
    with MockitoSugar
    with BeforeAndAfterAll {

  var router: ActorRef = _
  var router1: ActorRef = _
  var services: BeamServices = _
  var config: Config = _
  var iterationConfig: Config = _
  var scenario: Scenario = _

  val maxHour = TimeUnit.SECONDS.toHours(new TravelTimeCalculatorConfigGroup().getMaxTime).toInt

  override def beforeAll: Unit = {
    config = baseConfig
      .withValue("beam.warmStart.enabled", ConfigValueFactory.fromAnyRef(true))
      .withValue("beam.warmStart.path", ConfigValueFactory.fromAnyRef("test/input/beamville/test-data"))
    val beamConfig = BeamConfig(config)

    // Have to mock a lot of things to get the router going
    services = mock[BeamServices](withSettings().stubOnly())
    scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig())
    when(services.beamConfig).thenReturn(beamConfig)
    when(services.geo).thenReturn(new GeoUtilsImpl(beamConfig))
    when(services.personHouseholds).thenReturn(Map[Id[Person], Household]())
    when(services.agencyAndRouteByVehicleIds).thenReturn(TrieMap[Id[Vehicle], (String, String)]())
    when(services.ptFares).thenReturn(PtFares(List[FareRule]()))
    when(services.dates).thenReturn(
      DateUtils(
        ZonedDateTime.parse(beamConfig.beam.routing.baseDate).toLocalDateTime,
        ZonedDateTime.parse(beamConfig.beam.routing.baseDate)
      )
    )
    when(services.vehicleTypes).thenReturn(Map[Id[BeamVehicleType], BeamVehicleType]())
    when(services.fuelTypePrices).thenReturn(Map[FuelType, Double]().withDefaultValue(0.0))
    var networkCoordinator = new DefaultNetworkCoordinator(beamConfig)
    networkCoordinator.loadNetwork()
    networkCoordinator.convertFrequenciesToTrips()

    val networkHelper = new NetworkHelperImpl(networkCoordinator.network)
    when(services.networkHelper).thenReturn(networkHelper)

    val fareCalculator = mock[FareCalculator]
    when(fareCalculator.getFareSegments(any(), any(), any(), any(), any())).thenReturn(Vector[BeamFareSegment]())
    val tollCalculator = mock[TollCalculator]
    when(tollCalculator.calcTollByOsmIds(any())).thenReturn(0.0)
    router = system.actorOf(
      BeamRouter.props(
        services,
        networkCoordinator.transportNetwork,
        networkCoordinator.network,
        scenario,
        new EventsManagerImpl(),
        scenario.getTransitVehicles,
        fareCalculator,
        tollCalculator
      )
    )

    within(60 seconds) { // Router can take a while to initialize
      router ! Identify(0)
      expectMsgType[ActorIdentity]
    }

    val path = beamConfig.beam.outputs.baseOutputDirectory + beamConfig.beam.agentsim.simulationName + FileUtils
      .getOptionalOutputPathSuffix(true)

    iterationConfig = config.withValue("beam.warmStart.path", ConfigValueFactory.fromAnyRef(path))
    val configBuilder = new MatSimBeamConfigBuilder(iterationConfig)
    val matsimConfig = configBuilder.buildMatSamConf()
    matsimConfig.controler().setLastIteration(2)
    matsimConfig.controler.setOutputDirectory(path)
    networkCoordinator = new DefaultNetworkCoordinator(BeamConfig(iterationConfig))
    networkCoordinator.loadNetwork()
    networkCoordinator.convertFrequenciesToTrips()

    scenario = ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]
    val injector = org.matsim.core.controler.Injector.createInjector(
      matsimConfig,
      new AbstractModule() {
        override def install(): Unit = {
          install(module(iterationConfig, scenario, networkCoordinator, networkHelper))
        }
      }
    )
    val bs = injector.getInstance(classOf[BeamServices])
    DefaultPopulationAdjustment(bs).update(scenario)
    bs.controler.run()
    router1 = system.actorOf(
      BeamRouter.props(
        services,
        networkCoordinator.transportNetwork,
        networkCoordinator.network,
        scenario,
        new EventsManagerImpl(),
        scenario.getTransitVehicles,
        fareCalculator,
        tollCalculator
      )
    )
    within(60 seconds) { // Router can take a while to initialize
      router1 ! Identify(0)
      expectMsgType[ActorIdentity]
    }
  }

  "A warmStart router" must {
    val origin = new BeamRouter.Location(166321.9, 1568.87)
    val destination = new BeamRouter.Location(167138.4, 1117)
    val time = 3000

    "take given link traversal times into account" in {
      router ! RoutingRequest(
        origin,
        destination,
        time,
        Vector(),
        Vector(
          StreetVehicle(
            Id.createVehicleId("car"),
            BeamVehicleType.defaultCarBeamVehicleType.id,
            new SpaceTime(origin, time),
            Modes.BeamMode.CAR,
            asDriver = true
          )
        )
      )
      var response = expectMsgType[RoutingResponse]
      assert(response.itineraries.exists(_.tripClassifier == CAR))
      val carOption = response.itineraries.find(_.tripClassifier == CAR).get
      assert(carOption.totalTravelTimeInSecs == 145)

      BeamWarmStart(services.beamConfig, maxHour).warmStartTravelTime(router, scenario)

      router ! RoutingRequest(
        origin,
        destination,
        time,
        Vector(),
        Vector(
          StreetVehicle(
            Id.createVehicleId("car"),
            BeamVehicleType.defaultCarBeamVehicleType.id,
            new SpaceTime(origin, time),
            Modes.BeamMode.CAR,
            asDriver = true
          )
        )
      )
      response = expectMsgType[RoutingResponse]

      assert(response.itineraries.exists(_.tripClassifier == CAR))
      val carOption2 = response.itineraries.find(_.tripClassifier == CAR).get
      assert(carOption2.totalTravelTimeInSecs == 105)
    }

    "show a decrease in travel time after three iterations if warm start times are doubled" in {

      BeamWarmStart(
        BeamConfig(
          config.withValue(
            "beam.warmStart.path",
            ConfigValueFactory.fromAnyRef("test/input/beamville/test-data/double-time")
          )
        ),
        maxHour
      ).warmStartTravelTime(router, scenario)

      router ! RoutingRequest(
        origin,
        destination,
        time,
        Vector(),
        Vector(
          StreetVehicle(
            Id.createVehicleId("car"),
            BeamVehicleType.defaultCarBeamVehicleType.id,
            new SpaceTime(origin, time),
            Modes.BeamMode.CAR,
            asDriver = true
          )
        )
      )
      var response = expectMsgType[RoutingResponse]
      assert(response.itineraries.exists(_.tripClassifier == CAR))
      val carOption = response.itineraries.find(_.tripClassifier == CAR).get
      assert(carOption.totalTravelTimeInSecs == 203)

      BeamWarmStart(BeamConfig(iterationConfig), maxHour).warmStartTravelTime(router, scenario)
      router1 ! RoutingRequest(
        origin,
        destination,
        time,
        Vector(),
        Vector(
          StreetVehicle(
            Id.createVehicleId("car"),
            BeamVehicleType.defaultCarBeamVehicleType.id,
            new SpaceTime(origin, time),
            Modes.BeamMode.CAR,
            asDriver = true
          )
        )
      )
      response = expectMsgType[RoutingResponse]
      assert(response.itineraries.exists(_.tripClassifier == CAR))
      val carOption2 = response.itineraries.find(_.tripClassifier == CAR).get
      assert(carOption2.totalTravelTimeInSecs < carOption.totalTravelTimeInSecs)
    }

    "show an increase in travel time after three iterations if warm start times are cut in half" in {

      BeamWarmStart(
        BeamConfig(
          config
            .withValue("beam.warmStart.path", ConfigValueFactory.fromAnyRef("test/input/beamville/test-data/half-time"))
        ),
        maxHour
      ).warmStartTravelTime(router, scenario)

      router ! RoutingRequest(
        origin,
        destination,
        time,
        Vector(),
        Vector(
          StreetVehicle(
            Id.createVehicleId("car"),
            BeamVehicleType.defaultCarBeamVehicleType.id,
            new SpaceTime(origin, time),
            Modes.BeamMode.CAR,
            asDriver = true
          )
        )
      )
      var response = expectMsgType[RoutingResponse]
      assert(response.itineraries.exists(_.tripClassifier == CAR))
      val carOption = response.itineraries.find(_.tripClassifier == CAR).get

      BeamWarmStart(BeamConfig(iterationConfig), maxHour).warmStartTravelTime(router, scenario)
      router1 ! RoutingRequest(
        origin,
        destination,
        time,
        Vector(),
        Vector(
          StreetVehicle(
            Id.createVehicleId("car"),
            BeamVehicleType.defaultCarBeamVehicleType.id,
            new SpaceTime(origin, time),
            Modes.BeamMode.CAR,
            asDriver = true
          )
        )
      )
      response = expectMsgType[RoutingResponse]
      assert(response.itineraries.exists(_.tripClassifier == CAR))
      val carOption2 = response.itineraries.find(_.tripClassifier == CAR).get
      assert(carOption2.totalTravelTimeInSecs > carOption.totalTravelTimeInSecs)

    }

    "path became faster by reducing travel time" in {

      val destination = new BeamRouter.Location(167138.4, 908)

      router ! RoutingRequest(
        origin,
        destination,
        time,
        Vector(),
        Vector(
          StreetVehicle(
            Id.createVehicleId("car"),
            BeamVehicleType.defaultCarBeamVehicleType.id,
            new SpaceTime(origin, time),
            Modes.BeamMode.CAR,
            asDriver = true
          )
        )
      )

      var response = expectMsgType[RoutingResponse]
      assert(response.itineraries.exists(_.tripClassifier == CAR))
      val carOption = response.itineraries.find(_.tripClassifier == CAR).get
      val links = carOption.beamLegs().head.travelPath.linkIds
      val travelTime1 = carOption.beamLegs().head.travelPath.linkTravelTime.sum

      BeamWarmStart(
        BeamConfig(
          config.withValue(
            "beam.warmStart.path",
            ConfigValueFactory.fromAnyRef("test/input/beamville/test-data/reduce10x-time")
          )
        ),
        maxHour
      ).warmStartTravelTime(router, scenario)

      router ! RoutingRequest(
        origin,
        destination,
        time,
        Vector(),
        Vector(
          StreetVehicle(
            Id.createVehicleId("car"),
            BeamVehicleType.defaultCarBeamVehicleType.id,
            new SpaceTime(origin, time),
            Modes.BeamMode.CAR,
            asDriver = true
          )
        )
      )
      response = expectMsgType[RoutingResponse]

      assert(response.itineraries.exists(_.tripClassifier == CAR))
      val carOption2 = response.itineraries.find(_.tripClassifier == CAR).get
      val newLinks = carOption2.beamLegs().head.travelPath.linkIds
      val travelTime2 = carOption2.beamLegs().head.travelPath.linkTravelTime.sum
      assert(travelTime2 <= travelTime1)
      assert(!links.equals(newLinks))
    }
  }

  override def afterAll: Unit = {
    shutdown()
  }

}
