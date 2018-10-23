package beam.router

import java.time.ZonedDateTime

import akka.actor.{ActorIdentity, ActorRef, ActorSystem, Identify}
import akka.testkit.{ImplicitSender, TestKit}
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.integration.IntegrationSpecCommon
import beam.router.BeamRouter._
import beam.router.Modes.BeamMode.CAR
import beam.router.gtfs.FareCalculator
import beam.router.gtfs.FareCalculator.BeamFareSegment
import beam.router.model.RoutingModel
import beam.router.osm.TollCalculator
import beam.router.r5.NetworkCoordinator
import beam.sim.common.GeoUtilsImpl
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.{BeamHelper, BeamServices, BeamWarmStart}
import beam.utils.{DateUtils, FileUtils}
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.{Config, ConfigValueFactory}
import org.matsim.api.core.v01.{Coord, Id, Scenario}
import org.matsim.core.config.ConfigUtils
import org.matsim.core.controler.AbstractModule
import org.matsim.core.events.EventsManagerImpl
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.concurrent.duration._
import scala.language.postfixOps

class WarmStartRoutingSpec
  extends TestKit(
    ActorSystem(
      "WarmStartRoutingSpec",
      testConfig("test/input/beamville/beam.conf")
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

  override def beforeAll: Unit = {
    config = baseConfig
      .withValue("beam.warmStart.enabled", ConfigValueFactory.fromAnyRef(true))
      .withValue("beam.warmStart.path", ConfigValueFactory.fromAnyRef("test/input/beamville/test-data"))
    val beamConfig = BeamConfig(config)

    // Have to mock a lot of things to get the router going
    services = mock[BeamServices]
    var scenario: Scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig())
    when(services.beamConfig).thenReturn(beamConfig)
    when(services.geo).thenReturn(new GeoUtilsImpl(services))
    when(services.dates).thenReturn(
      DateUtils(
        ZonedDateTime.parse(beamConfig.beam.routing.baseDate).toLocalDateTime,
        ZonedDateTime.parse(beamConfig.beam.routing.baseDate)
      )
    )
    var networkCoordinator = new NetworkCoordinator(beamConfig)
    networkCoordinator.loadNetwork()

    val fareCalculator = mock[FareCalculator]
    when(fareCalculator.getFareSegments(any(), any(), any(), any(), any())).thenReturn(Vector[BeamFareSegment]())
    val tollCalculator = mock[TollCalculator]
    when(tollCalculator.calcToll(any())).thenReturn(0.0)
    router = system.actorOf(
      BeamRouter.props(
        services,
        networkCoordinator.transportNetwork,
        networkCoordinator.network,
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
    when(services.beamRouter).thenReturn(router)

    val path = beamConfig.beam.outputs.baseOutputDirectory + beamConfig.beam.agentsim.simulationName + FileUtils
      .getOptionalOutputPathSuffix(true)

    iterationConfig = config.withValue("beam.warmStart.path", ConfigValueFactory.fromAnyRef(path))
    val configBuilder = new MatSimBeamConfigBuilder(iterationConfig)
    val matsimConfig = configBuilder.buildMatSamConf()
    matsimConfig.controler().setLastIteration(2)
    matsimConfig.controler.setOutputDirectory(path)
    networkCoordinator = new NetworkCoordinator(BeamConfig(iterationConfig))
    networkCoordinator.loadNetwork()
    scenario = ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]
    val injector = org.matsim.core.controler.Injector.createInjector(
      matsimConfig,
      new AbstractModule() {
        override def install(): Unit = {
          install(module(iterationConfig, scenario, networkCoordinator))
        }
      }
    )

    val controler = injector.getInstance(classOf[BeamServices]).controler
    controler.run()
    router1 = system.actorOf(
      BeamRouter.props(
        services,
        networkCoordinator.transportNetwork,
        networkCoordinator.network,
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
    val time = RoutingModel.DiscreteTime(3000)


    "take given link traversal times into account" in {
      router ! RoutingRequest(
        origin,
        destination,
        time,
        Vector(),
        Vector(
          StreetVehicle(
            Id.createVehicleId("car"),
            new SpaceTime(origin, time.atTime),
            Modes.BeamMode.CAR,
            asDriver = true
          )
        )
      )
      var response = expectMsgType[RoutingResponse]
      assert(response.itineraries.exists(_.tripClassifier == CAR))
      val carOption = response.itineraries.find(_.tripClassifier == CAR).get
      assert(carOption.totalTravelTimeInSecs == 76)

      BeamWarmStart(services.beamConfig).warmStartTravelTime(services.beamRouter)

      router ! RoutingRequest(
        origin,
        destination,
        time,
        Vector(),
        Vector(
          StreetVehicle(
            Id.createVehicleId("car"),
            new SpaceTime(origin, time.atTime),
            Modes.BeamMode.CAR,
            asDriver = true
          )
        )
      )
      response = expectMsgType[RoutingResponse]

      assert(response.itineraries.exists(_.tripClassifier == CAR))
      val carOption2 = response.itineraries.find(_.tripClassifier == CAR).get
      assert(carOption2.totalTravelTimeInSecs == 55)
    }

    "show a decrease in travel time after three iterations if warm start times are doubled" in {


      BeamWarmStart(BeamConfig(
        config.withValue("beam.warmStart.path", ConfigValueFactory.fromAnyRef("test/input/beamville/test-data/double-time")))
      ).warmStartTravelTime(services.beamRouter)

      router ! RoutingRequest(
        origin,
        destination,
        time,
        Vector(),
        Vector(
          StreetVehicle(
            Id.createVehicleId("car"),
            new SpaceTime(origin, time.atTime),
            Modes.BeamMode.CAR,
            asDriver = true
          )
        )
      )
      var response = expectMsgType[RoutingResponse]
      assert(response.itineraries.exists(_.tripClassifier == CAR))
      val carOption = response.itineraries.find(_.tripClassifier == CAR).get
      assert(carOption.totalTravelTimeInSecs == 110)

      BeamWarmStart(BeamConfig(iterationConfig)).warmStartTravelTime(services.beamRouter)
      router1 ! RoutingRequest(
        origin,
        destination,
        time,
        Vector(),
        Vector(
          StreetVehicle(
            Id.createVehicleId("car"),
            new SpaceTime(origin, time.atTime),
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

      BeamWarmStart(BeamConfig(
        config.withValue("beam.warmStart.path", ConfigValueFactory.fromAnyRef("test/input/beamville/test-data/half-time")))
      ).warmStartTravelTime(services.beamRouter)

      router ! RoutingRequest(
        origin,
        destination,
        time,
        Vector(),
        Vector(
          StreetVehicle(
            Id.createVehicleId("car"),
            new SpaceTime(origin, time.atTime),
            Modes.BeamMode.CAR,
            asDriver = true
          )
        )
      )
      var response = expectMsgType[RoutingResponse]
      assert(response.itineraries.exists(_.tripClassifier == CAR))
      val carOption = response.itineraries.find(_.tripClassifier == CAR).get

      BeamWarmStart(BeamConfig(iterationConfig)).warmStartTravelTime(services.beamRouter)
      router1 ! RoutingRequest(
        origin,
        destination,
        time,
        Vector(),
        Vector(
          StreetVehicle(
            Id.createVehicleId("car"),
            new SpaceTime(origin, time.atTime),
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
      val time = RoutingModel.DiscreteTime(300000)
      router ! RoutingRequest(
        origin,
        destination,
        time,
        Vector(),
        Vector(
          StreetVehicle(
            Id.createVehicleId("car"),
            new SpaceTime(origin, time.atTime),
            Modes.BeamMode.CAR,
            asDriver = true
          )
        )
      )

      var response = expectMsgType[RoutingResponse]
      assert(response.itineraries.exists(_.tripClassifier == CAR))
      val carOption = response.itineraries.find(_.tripClassifier == CAR).get
      val links = carOption.beamLegs().head.travelPath.linkIds
      val travelTime1 = carOption.beamLegs().head.travelPath.linkTravelTime.reduce((x,y) => x+y)

      BeamWarmStart(BeamConfig(
        config.withValue("beam.warmStart.path", ConfigValueFactory.fromAnyRef("test/input/beamville/test-data/reduce10x-time")))
      ).warmStartTravelTime(services.beamRouter)

      router ! RoutingRequest(
        origin,
        destination,
        time,
        Vector(),
        Vector(
          StreetVehicle(
            Id.createVehicleId("car"),
            new SpaceTime(origin, time.atTime),
            Modes.BeamMode.CAR,
            asDriver = true
          )
        )
      )
      response = expectMsgType[RoutingResponse]

      assert(response.itineraries.exists(_.tripClassifier == CAR))
      val carOption2 = response.itineraries.find(_.tripClassifier == CAR).get
      val newLinks = carOption2.beamLegs().head.travelPath.linkIds
      val travelTime2 = carOption2.beamLegs().head.travelPath.linkTravelTime.reduce((x,y) => x+y)
      assert(travelTime2 < travelTime1)
      assert(!links.equals(newLinks))


    }


  }

  override def afterAll: Unit = {
    shutdown()
  }

}