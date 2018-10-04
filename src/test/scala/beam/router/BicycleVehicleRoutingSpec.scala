package beam.router

import java.time.ZonedDateTime

import akka.actor.{ActorIdentity, ActorRef, ActorSystem, Identify}
import akka.testkit.{ImplicitSender, TestKit}
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter._
import beam.router.Modes.BeamMode
import beam.router.model.{BeamLeg, BeamPath}
import beam.router.gtfs.FareCalculator
import beam.router.gtfs.FareCalculator.BeamFareSegment
import beam.router.model.RoutingModel
import beam.router.osm.TollCalculator
import beam.router.r5.NetworkCoordinator
import beam.sim.BeamServices
import beam.sim.common.GeoUtilsImpl
import beam.sim.config.BeamConfig
import beam.utils.{BeamConfigUtils, DateUtils}
import org.matsim.api.core.v01.Id
import org.matsim.core.config.ConfigUtils
import org.matsim.core.events.EventsManagerImpl
import org.matsim.core.scenario.ScenarioUtils
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.concurrent.duration._
import scala.language.postfixOps

class BicycleVehicleRoutingSpec
    extends TestKit(
      ActorSystem(
        "BicycleVehicleRoutingSpec",
        BeamConfigUtils
          .parseFileSubstitutingInputDirectory("test/input/beamville/beam.conf")
          .resolve()
      )
    )
    with WordSpecLike
    with Matchers
    with ImplicitSender
    with MockitoSugar
    with BeforeAndAfterAll {

  var router: ActorRef = _
  var networkCoordinator: NetworkCoordinator = _

  override def beforeAll: Unit = {
    val beamConfig = BeamConfig(system.settings.config)

    // Have to mock a lot of things to get the router going
    val services: BeamServices = mock[BeamServices]
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
  }

  "A time-dependent router" must {
    val origin = new BeamRouter.Location(166321.9, 1568.87)
    val destination = new BeamRouter.Location(167138.4, 1117)
    val time = RoutingModel.DiscreteTime(3000)

    "give updated travel times for a given route" in {
      val leg = BeamLeg(
        3000,
        BeamMode.BIKE,
        0,
        BeamPath(
          Vector(143, 60, 58, 62, 80, 74, 68, 154),
          Vector(), // TODO FIXME
          None,
          SpaceTime(166321.9, 1568.87, 3000),
          SpaceTime(167138.4, 1117, 3000),
          0.0
        )
      )
      router ! EmbodyWithCurrentTravelTime(leg, Id.createVehicleId(1))
      val response = expectMsgType[RoutingResponse]
      assert(response.itineraries.head.beamLegs().head.duration == 285)
      // R5 travel time, but less than what's in R5's routing response (see vv),
      // presumably because the first/last edge are not travelled (in R5, trip starts on a "split")
      print("ok")
    }

  }

  override def afterAll: Unit = {
    shutdown()
  }

}
