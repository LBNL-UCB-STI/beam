package beam.router

import java.time.ZonedDateTime

import akka.actor.{ActorIdentity, ActorRef, ActorSystem, Identify}
import akka.testkit.{ImplicitSender, TestKit}
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter._
import beam.router.Modes.BeamMode.CAR
import beam.router.gtfs.FareCalculator
import beam.router.gtfs.FareCalculator.BeamFareSegment
import beam.router.model.RoutingModel
import beam.router.osm.TollCalculator
import beam.router.r5.NetworkCoordinator
import beam.sim.BeamServices
import beam.sim.common.GeoUtilsImpl
import beam.sim.config.BeamConfig
import beam.utils.DateUtils
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigValueFactory
import org.matsim.api.core.v01.{Coord, Id, Scenario}
import org.matsim.core.config.ConfigUtils
import org.matsim.core.events.EventsManagerImpl
import org.matsim.core.scenario.ScenarioUtils
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.concurrent.duration._
import scala.language.postfixOps

class TollRoutingSpec
    extends TestKit(
      ActorSystem("TollRoutingSpec", testConfig("test/input/beamville/beam.conf"))
    )
    with WordSpecLike
    with Matchers
    with ImplicitSender
    with MockitoSugar
    with BeforeAndAfterAll {

  var router: ActorRef = _
  var networkCoordinator: NetworkCoordinator = _

  val services: BeamServices = mock[BeamServices]
  var scenario: Scenario = _
  var fareCalculator: FareCalculator = _

  override def beforeAll: Unit = {
    val beamConfig = BeamConfig(system.settings.config)

    // Have to mock a lot of things to get the router going
    scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig())
    when(services.beamConfig).thenReturn(beamConfig)
    when(services.geo).thenReturn(new GeoUtilsImpl(services))
    when(services.dates).thenReturn(DateUtils(ZonedDateTime.parse(beamConfig.beam.routing.baseDate).toLocalDateTime, ZonedDateTime.parse(beamConfig.beam.routing.baseDate)))
    networkCoordinator = new NetworkCoordinator(beamConfig)
    networkCoordinator.loadNetwork()

    fareCalculator = mock[FareCalculator]
    when(fareCalculator.getFareSegments(any(), any(), any(), any(), any())).thenReturn(Vector[BeamFareSegment]())
    val tollCalculator = new TollCalculator(beamConfig,"test/input/beamville/r5")
    router = system.actorOf(BeamRouter.props(services, networkCoordinator.transportNetwork, networkCoordinator.network, new EventsManagerImpl(), scenario.getTransitVehicles, fareCalculator, tollCalculator))

    within(60 seconds) { // Router can take a while to initialize
      router ! Identify(0)
      expectMsgType[ActorIdentity]
    }
  }

  "A time-dependent router with toll calculator" must {
    val time = RoutingModel.DiscreteTime(3000)

    "report a toll on a route where the fastest route has tolls" in {
      val origin = new Location(0.00005, 0.01995)
      val destination = new Location(0.02005, 0.01995)
      val request = RoutingRequest(origin, destination, time, Vector(), Vector(StreetVehicle(Id.createVehicleId("car"), new SpaceTime(new Coord(origin.getX, origin.getY), time.atTime), Modes.BeamMode.CAR, asDriver = true)))
      router ! request
      val response = expectMsgType[RoutingResponse]
      val carOption = response.itineraries.find(_.tripClassifier == CAR).get
      assert(carOption.costEstimate == 3.0) // contains three toll links: two specified in OSM, and one in CSV file

      val configWithTollTurnedUp = BeamConfig(system.settings.config
        .withValue("beam.agentsim.tuning.tollPrice", ConfigValueFactory.fromAnyRef(2.0)))
      val moreExpensiveTollCalculator = new TollCalculator(configWithTollTurnedUp,"test/input/beamville/r5")
      val moreExpensiveRouter = system.actorOf(BeamRouter.props(services, networkCoordinator.transportNetwork, networkCoordinator.network, new EventsManagerImpl(), scenario.getTransitVehicles, fareCalculator, moreExpensiveTollCalculator))
      within(60 seconds) { // Router can take a while to initialize
        moreExpensiveRouter ! Identify(0)
        expectMsgType[ActorIdentity]
      }
      moreExpensiveRouter ! request
      val moreExpensiveResponse = expectMsgType[RoutingResponse]
      val moreExpensiveCarOption = moreExpensiveResponse.itineraries.find(_.tripClassifier == CAR).get
      // the factor in the config only applies to link tolls at the moment, i.e. one of the three paid is 2.0
      assert(moreExpensiveCarOption.costEstimate == 4.0)
    }

  }

  override def afterAll: Unit = {
    shutdown()
  }

}
