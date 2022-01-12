package beam.router

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{ImplicitSender, TestKit}
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter._
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{CAR, WALK}
import beam.router.gtfs.FareCalculator
import beam.router.gtfs.FareCalculator.BeamFareSegment
import beam.router.osm.TollCalculator
import beam.router.r5.DefaultNetworkCoordinator
import beam.sim.common.GeoUtilsImpl
import beam.sim.config.BeamConfig
import beam.sim.population.{AttributesOfIndividual, HouseholdAttributes}
import beam.sim.{BeamHelper, BeamScenario}
import beam.utils.NetworkHelperImpl
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigValueFactory
import org.matsim.api.core.v01.{Coord, Id, Scenario}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.config.ConfigUtils
import org.matsim.core.controler.OutputDirectoryHierarchy
import org.matsim.core.scenario.ScenarioUtils
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.language.postfixOps

class TollRoutingSpec
    extends TestKit(
      ActorSystem("TollRoutingSpec", testConfig("test/input/beamville/beam.conf").resolve())
    )
    with AnyWordSpecLike
    with Matchers
    with BeamHelper
    with ImplicitSender
    with BeforeAndAfterAll {

  var router: ActorRef = _
  var networkCoordinator: DefaultNetworkCoordinator = _
  var scenario: Scenario = _
  var beamScenario: BeamScenario = _
  var fareCalculator: FareCalculator = _

  override def beforeAll(): Unit = {
    val beamConfig = BeamConfig(system.settings.config)
    beamScenario = loadScenario(beamConfig)
      .copy(fuelTypePrices = Map().withDefaultValue(0.0)) // Reset fuel prices to 0 so we get pure monetary costs

    // Have to mock a lot of things to get the router going
    scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig())
    networkCoordinator = DefaultNetworkCoordinator(beamConfig)
    networkCoordinator.loadNetwork()
    networkCoordinator.convertFrequenciesToTrips()

    val networkHelper = new NetworkHelperImpl(networkCoordinator.network)

    fareCalculator = mock(classOf[FareCalculator])
    when(fareCalculator.getFareSegments(any(), any(), any(), any(), any())).thenReturn(Vector[BeamFareSegment]())
    val tollCalculator = new TollCalculator(beamConfig)
    router = system.actorOf(
      BeamRouter.props(
        beamScenario,
        networkCoordinator.transportNetwork,
        networkCoordinator.network,
        networkHelper,
        new GeoUtilsImpl(beamConfig),
        fareCalculator,
        tollCalculator,
        eventsManager = mock(classOf[EventsManager]),
        ioController = mock(classOf[OutputDirectoryHierarchy])
      )
    )
  }

  "A time-dependent router with toll calculator" must {
    val time = 3000
    val origin = new Location(166027.034662, 2208.12088093) // In WGS this would be Location(0.00005, 0.01995)
    val destination = new Location(168255.58799, 2208.08034995) // In WGS Location(0.02005, 0.01995)

    "report a toll on a route where the fastest route has tolls" in {
      val request = RoutingRequest(
        origin,
        destination,
        time,
        withTransit = false,
        personId = None,
        Vector(
          StreetVehicle(
            Id.createVehicleId("car"),
            Id.create("beamVilleCar", classOf[BeamVehicleType]),
            new SpaceTime(new Coord(origin.getX, origin.getY), time),
            Modes.BeamMode.CAR,
            asDriver = true,
            needsToCalculateCost = true
          )
        ),
        attributesOfIndividual = Some(
          AttributesOfIndividual(
            HouseholdAttributes.EMPTY,
            None,
            true,
            Vector(BeamMode.CAR),
            valueOfTime = 10000000.0, // I don't mind tolls at all
            None,
            None
          )
        ),
        triggerId = 0
      )
      router ! request
      val response = expectMsgType[RoutingResponse]
      val carOption = response.itineraries.find(_.tripClassifier == CAR).get
      assert(carOption.costEstimate == 3.0, "contains three toll links: two specified in OSM, and one in CSV file")
      assert(carOption.totalTravelTimeInSecs == 142)

      val earlierRequest = request.copy(departureTime = 2000)
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
          beamScenario,
          networkCoordinator.transportNetwork,
          networkCoordinator.network,
          new NetworkHelperImpl(networkCoordinator.network),
          new GeoUtilsImpl(beamScenario.beamConfig),
          fareCalculator,
          moreExpensiveTollCalculator,
          eventsManager = mock(classOf[EventsManager]),
          ioController = mock(classOf[OutputDirectoryHierarchy])
        )
      )
      moreExpensiveRouter ! request
      val moreExpensiveResponse = expectMsgType[RoutingResponse]
      val moreExpensiveCarOption = moreExpensiveResponse.itineraries.find(_.tripClassifier == CAR).get
      // the factor in the config only applies to link tolls at the moment, i.e. one of the three paid is 2.0
      assert(moreExpensiveCarOption.costEstimate == 4.0)

      val tollSensitiveRequest = RoutingRequest(
        originUTM = origin,
        destinationUTM = destination,
        departureTime = time,
        withTransit = false,
        streetVehicles = Vector(
          StreetVehicle(
            Id.createVehicleId("car"),
            Id.create("beamVilleCar", classOf[BeamVehicleType]),
            new SpaceTime(new Coord(origin.getX, origin.getY), time),
            Modes.BeamMode.CAR,
            asDriver = true,
            needsToCalculateCost = true
          )
        ),
        attributesOfIndividual = Some(
          AttributesOfIndividual(
            HouseholdAttributes.EMPTY,
            None,
            true,
            Vector(BeamMode.CAR),
            // If 1$ is worth more than 144 seconds to me, I should be sent on the alternative route
            // (which takes 288 seconds)
            valueOfTime = 3600.0 / 145.0,
            None,
            None
          )
        ),
        triggerId = 0
      )
      router ! tollSensitiveRequest
      val tollSensitiveResponse = expectMsgType[RoutingResponse]
      val tollSensitiveCarOption = tollSensitiveResponse.itineraries.find(_.tripClassifier == CAR).get
      assert(tollSensitiveCarOption.costEstimate <= 2.0, "if I'm toll sensitive, I don't go over the tolled link")
      assert(tollSensitiveCarOption.totalTravelTimeInSecs == 284)
    }

    "not report a toll when walking" in {
      val request = RoutingRequest(
        originUTM = origin,
        destinationUTM = destination,
        departureTime = time,
        withTransit = false,
        streetVehicles = Vector(
          StreetVehicle(
            Id.createVehicleId("body"),
            Id.create("beamVilleCar", classOf[BeamVehicleType]),
            new SpaceTime(new Coord(origin.getX, origin.getY), time),
            Modes.BeamMode.WALK,
            asDriver = true,
            needsToCalculateCost = false
          )
        ),
        triggerId = 0
      )
      router ! request
      val response = expectMsgType[RoutingResponse]
      val walkOption = response.itineraries.find(_.tripClassifier == WALK).get
      assert(walkOption.costEstimate == 0.0)
    }

  }

  override def afterAll(): Unit = {
    shutdown()
  }

}
