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
import beam.router.osm.TollCalculator
import beam.router.r5.NetworkCoordinator
import beam.sim.common.GeoUtilsImpl
import beam.sim.config.BeamConfig
import beam.sim.{BeamServices, BeamWarmStart}
import beam.utils.DateUtils
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigValueFactory
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.config.ConfigUtils
import org.matsim.core.events.EventsManagerImpl
import org.matsim.core.scenario.ScenarioUtils
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
          .withValue("beam.warmStart.pathType", ConfigValueFactory.fromAnyRef("ABSOLUTE_PATH"))
          .withValue(
            "beam.warmStart.path",
            ConfigValueFactory
              .fromAnyRef("test/input/beamville/test-data/beamville.linkstats.csv.gz")
          )
      )
    )
    with WordSpecLike
    with Matchers
    with ImplicitSender
    with IntegrationSpecCommon
    with MockitoSugar
    with BeforeAndAfterAll {

  var router: ActorRef = _
  var networkCoordinator: NetworkCoordinator = _
  var services: BeamServices = _

  override def beforeAll: Unit = {
    val config = baseConfig
      .withValue("beam.warmStart.enabled", ConfigValueFactory.fromAnyRef(true))
      .withValue("beam.warmStart.pathType", ConfigValueFactory.fromAnyRef("ABSOLUTE_PATH"))
      .withValue(
        "beam.warmStart.path",
        ConfigValueFactory
          .fromAnyRef("test/input/beamville/test-data/beamville.linkstats.csv.gz")
      )
    val beamConfig = BeamConfig(config)

    // Have to mock a lot of things to get the router going
    services = mock[BeamServices]
    val scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig())
    when(services.beamConfig).thenReturn(beamConfig)
    when(services.geo).thenReturn(new GeoUtilsImpl(services))
    when(services.dates).thenReturn(
      DateUtils(
        ZonedDateTime.parse(beamConfig.beam.routing.baseDate).toLocalDateTime,
        ZonedDateTime.parse(beamConfig.beam.routing.baseDate)
      )
    )
    networkCoordinator = new NetworkCoordinator(beamConfig)
    networkCoordinator.loadNetwork()

    val fareCalculator = mock[FareCalculator]
    when(fareCalculator.getFareSegments(any(), any(), any(), any(), any()))
      .thenReturn(Vector[BeamFareSegment]())
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
            new SpaceTime(new Coord(origin.getX, origin.getY), time.atTime),
            Modes.BeamMode.CAR,
            asDriver = true
          )
        )
      )
      val response = expectMsgType[RoutingResponse]
      assert(response.itineraries.exists(_.tripClassifier == CAR))
      val carOption = response.itineraries.find(_.tripClassifier == CAR).get
      assert(carOption.totalTravelTimeInSecs == 76)

      new BeamWarmStart(services.beamConfig).warmStartRouterIfNeeded(services.beamRouter)
      router ! RoutingRequest(
        origin,
        destination,
        time,
        Vector(),
        Vector(
          StreetVehicle(
            Id.createVehicleId("car"),
            new SpaceTime(new Coord(origin.getX, origin.getY), time.atTime),
            Modes.BeamMode.CAR,
            asDriver = true
          )
        )
      )
      val response2 = expectMsgType[RoutingResponse]
      assert(response2.itineraries.exists(_.tripClassifier == CAR))
      val carOption2 = response2.itineraries.find(_.tripClassifier == CAR).get
      assert(carOption2.totalTravelTimeInSecs == 55)
    }
  }

  override def afterAll: Unit = {
    shutdown()
  }

}
