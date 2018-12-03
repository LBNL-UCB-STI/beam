package beam.router

import java.time.ZonedDateTime

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{ImplicitSender, TestKit}
import beam.agentsim.agents.choice.mode.PtFares
import beam.agentsim.agents.choice.mode.PtFares.FareRule
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter._
import beam.router.Modes.BeamMode.{CAR, WALK}
import beam.router.gtfs.FareCalculator
import beam.router.gtfs.FareCalculator.BeamFareSegment
import beam.router.model.RoutingModel
import beam.router.osm.TollCalculator
import beam.router.r5.DefaultNetworkCoordinator
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
import org.matsim.vehicles.Vehicle
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.collection.concurrent.TrieMap
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
  var networkCoordinator: DefaultNetworkCoordinator = _

  val services: BeamServices = mock[BeamServices](withSettings().stubOnly())
  var scenario: Scenario = _
  var fareCalculator: FareCalculator = _

  override def beforeAll: Unit = {
    val beamConfig = BeamConfig(system.settings.config)

    // Have to mock a lot of things to get the router going
    scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig())
    when(services.beamConfig).thenReturn(beamConfig)
    when(services.geo).thenReturn(new GeoUtilsImpl(services))
    when(services.agencyAndRouteByVehicleIds).thenReturn(TrieMap[Id[Vehicle], (String, String)]())
    when(services.ptFares).thenReturn(PtFares(Map[String, List[FareRule]]()))
    when(services.vehicles).thenReturn(TrieMap[Id[BeamVehicle], BeamVehicle]())
    when(services.dates).thenReturn(
      DateUtils(
        ZonedDateTime.parse(beamConfig.beam.routing.baseDate).toLocalDateTime,
        ZonedDateTime.parse(beamConfig.beam.routing.baseDate)
      )
    )
    networkCoordinator = DefaultNetworkCoordinator(beamConfig)
    networkCoordinator.loadNetwork()
    networkCoordinator.convertFrequenciesToTrips()

    fareCalculator = mock[FareCalculator]
    when(fareCalculator.getFareSegments(any(), any(), any(), any(), any())).thenReturn(Vector[BeamFareSegment]())
    val tollCalculator = new TollCalculator(beamConfig)
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
  }

  "A time-dependent router with toll calculator" must {
    val time = RoutingModel.DiscreteTime(3000)
    val origin = new Location(0.00005, 0.01995)
    val destination = new Location(0.02005, 0.01995)

    "report a toll on a route where the fastest route has tolls" in {
      val timeValueOfMoney = 0.0 // I don't mind tolls
      val request = RoutingRequest(
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
      router ! request
      val response = expectMsgType[RoutingResponse]
      val carOption = response.itineraries.find(_.tripClassifier == CAR).get
      assert(carOption.costEstimate == 2.0, "contains three toll links: two specified in OSM, and one in CSV file")
      assert(carOption.totalTravelTimeInSecs == 288)

      val earlierRequest = request.copy(departureTime = RoutingModel.DiscreteTime(2000))
      router ! earlierRequest
      val earlierResponse = expectMsgType[RoutingResponse]
      val earlierCarOption = earlierResponse.itineraries.find(_.tripClassifier == CAR).get
      assert(earlierCarOption.costEstimate == 2.0, "the link toll starts at 3000; when we go earlier, we don't pay it")

      val configWithTollTurnedUp = BeamConfig(
        system.settings.config
          .withValue("beam.agentsim.tuning.tollPrice", ConfigValueFactory.fromAnyRef(2.0))
      )
      val moreExpensiveTollCalculator = new TollCalculator(configWithTollTurnedUp)
      val moreExpensiveRouter = system.actorOf(
        BeamRouter.props(
          services,
          networkCoordinator.transportNetwork,
          networkCoordinator.network,
          scenario,
          new EventsManagerImpl(),
          scenario.getTransitVehicles,
          fareCalculator,
          moreExpensiveTollCalculator
        )
      )
      moreExpensiveRouter ! request
      val moreExpensiveResponse = expectMsgType[RoutingResponse]
      val moreExpensiveCarOption = moreExpensiveResponse.itineraries.find(_.tripClassifier == CAR).get
      // the factor in the config only applies to link tolls at the moment, i.e. one of the three paid is 2.0
      assert(moreExpensiveCarOption.costEstimate == 2.0)

      // If 1$ is worth more than 144 seconds to me, I should be sent on the alternative route
      // (which takes 288 seconds)
      val higherTimeValueOfMoney = 145.0
      val tollSensitiveRequest = RoutingRequest(
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
      router ! tollSensitiveRequest
      val tollSensitiveResponse = expectMsgType[RoutingResponse]
      val tollSensitiveCarOption = tollSensitiveResponse.itineraries.find(_.tripClassifier == CAR).get
      assert(tollSensitiveCarOption.costEstimate == 2.0, "if I'm toll sensitive, I don't go over the tolled link")
      assert(tollSensitiveCarOption.totalTravelTimeInSecs == 288)
    }

    "not report a toll when walking" in {
      val request = RoutingRequest(
        origin,
        destination,
        time,
        Vector(),
        Vector(
          StreetVehicle(
            Id.createVehicleId("body"),
            new SpaceTime(new Coord(origin.getX, origin.getY), time.atTime),
            Modes.BeamMode.WALK,
            asDriver = true
          )
        )
      )
      router ! request
      val response = expectMsgType[RoutingResponse]
      val walkOption = response.itineraries.find(_.tripClassifier == WALK).get
      assert(walkOption.costEstimate == 0.0)
    }

  }

  override def afterAll: Unit = {
    shutdown()
  }

}
