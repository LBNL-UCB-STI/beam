package beam.sflight

import java.time.ZonedDateTime

import akka.actor.{ActorIdentity, ActorRef, ActorSystem, Identify}
import akka.testkit.{ImplicitSender, TestKit}
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter._
import beam.router.gtfs.FareCalculator
import beam.router.gtfs.FareCalculator.BeamFareSegment
import beam.router.osm.TollCalculator
import beam.router.r5.NetworkCoordinator
import beam.router.{BeamRouter, Modes, RoutingModel}
import beam.sim.BeamServices
import beam.sim.common.GeoUtilsImpl
import beam.sim.config.BeamConfig
import beam.utils.{BeamConfigUtils, DateUtils}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.config.ConfigUtils
import org.matsim.core.events.EventsManagerImpl
import org.matsim.core.scenario.ScenarioUtils
import org.matsim.vehicles.VehicleUtils
import org.mockito.Matchers.any
import org.mockito.Mockito.when
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.concurrent.duration._
import scala.language.postfixOps

class MultiModalRoutingSpec extends TestKit(ActorSystem("router-test")) with WordSpecLike with Matchers
  with ImplicitSender with MockitoSugar with BeforeAndAfterAll {

  var router: ActorRef = _

  override def beforeAll: Unit = {
    val beamConfig = BeamConfig(BeamConfigUtils.parseFileSubstitutingInputDirectory("test/input/sf-light/sf-light.conf").resolve())

    // Have to mock a lot of things to get the router going
    val services: BeamServices = mock[BeamServices]
    val scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig())
    when(services.beamConfig).thenReturn(beamConfig)
    when(services.geo).thenReturn(new GeoUtilsImpl(services))
    when(services.dates).thenReturn(DateUtils(ZonedDateTime.parse(beamConfig.beam.routing.baseDate).toLocalDateTime, ZonedDateTime.parse(beamConfig.beam.routing.baseDate)))
    val networkCoordinator: NetworkCoordinator = new NetworkCoordinator(beamConfig, VehicleUtils.createVehiclesContainer())
    networkCoordinator.loadNetwork()

    val fareCalculator = mock[FareCalculator]
    when(fareCalculator.getFareSegments(any(), any(), any(),any())).thenReturn(Vector[BeamFareSegment]())
    val tollCalculator = mock[TollCalculator]
    when(tollCalculator.calcToll(any())).thenReturn(0.0)
    router = system.actorOf(BeamRouter.props(services, networkCoordinator.transportNetwork, networkCoordinator.network, new EventsManagerImpl(), scenario.getTransitVehicles, fareCalculator, tollCalculator))

    within(60 seconds) { // Router can take a while to initialize
      router ! Identify(0)
      expectMsgType[ActorIdentity]
    }
  }

  /*
   * IMPORTANT NOTE: This test will fail if there is no GTFS data included in the R5 data directory. Try that first.
   */
  "A multi-modal router" must {
    "return a route with a starting time consistent with profile request" in {
      val origin = new BeamRouter.Location(552788, 4179300) // -122.4007,37.7595
      val destination = new BeamRouter.Location(548918, 4182749) // -122.4444,37.7908
      val time = RoutingModel.WindowTime(100, 200)
      router ! RoutingRequest(RoutingRequestTripInfo(origin, destination, time, Vector(), Vector(StreetVehicle(Id.createVehicleId("body-667520-0"), new SpaceTime(new Coord(origin.getX, origin.getY), time.atTime), Modes.BeamMode.WALK, asDriver = true))))
      val response = expectMsgType[RoutingResponse]
      val routedStartTime = response.itineraries.head.beamLegs().head.startTime
      assert(routedStartTime >= 100 && routedStartTime <= 200)
    }
  }

  override def afterAll: Unit = {
    shutdown()
  }

}
