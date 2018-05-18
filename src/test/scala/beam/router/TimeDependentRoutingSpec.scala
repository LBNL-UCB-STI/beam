package beam.router

import java.time.ZonedDateTime

import akka.actor.{ActorIdentity, ActorRef, ActorSystem, Identify}
import akka.testkit.{ImplicitSender, TestKit}
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter._
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.CAR
import beam.router.RoutingModel.{BeamLeg, BeamPath}
import beam.router.gtfs.FareCalculator
import beam.router.gtfs.FareCalculator.BeamFareSegment
import beam.router.osm.TollCalculator
import beam.router.r5.NetworkCoordinator
import beam.sim.BeamServices
import beam.sim.common.GeoUtilsImpl
import beam.sim.config.BeamConfig
import beam.utils.DateUtils
import beam.utils.TestConfigUtils.testConfig
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.config.ConfigUtils
import org.matsim.core.events.{EventsManagerImpl, EventsUtils}
import org.matsim.core.scenario.ScenarioUtils
import org.matsim.core.trafficmonitoring.TravelTimeCalculator
import org.matsim.vehicles.{Vehicle, VehicleUtils}
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.concurrent.duration._
import scala.language.postfixOps

class TimeDependentRoutingSpec extends TestKit(ActorSystem("router-test", testConfig("test/input/beamville/beam.conf"))) with WordSpecLike with Matchers
  with ImplicitSender with MockitoSugar with BeforeAndAfterAll {

  var router: ActorRef = _
  var networkCoordinator: NetworkCoordinator = _

  override def beforeAll: Unit = {
    val beamConfig = BeamConfig(system.settings.config)

    // Have to mock a lot of things to get the router going
    val services: BeamServices = mock[BeamServices]
    val scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig())
    when(services.beamConfig).thenReturn(beamConfig)
    when(services.geo).thenReturn(new GeoUtilsImpl(services))
    when(services.dates).thenReturn(DateUtils(ZonedDateTime.parse(beamConfig.beam.routing.baseDate).toLocalDateTime, ZonedDateTime.parse(beamConfig.beam.routing.baseDate)))
    networkCoordinator = new NetworkCoordinator(beamConfig, VehicleUtils.createVehiclesContainer())
    networkCoordinator.loadNetwork()

    val fareCalculator = mock[FareCalculator]
    when(fareCalculator.getFareSegments(any(), any(), any(), any(), any())).thenReturn(Vector[BeamFareSegment]())
    val tollCalculator = mock[TollCalculator]
    when(tollCalculator.calcToll(any())).thenReturn(0.0)
    router = system.actorOf(BeamRouter.props(services, networkCoordinator.transportNetwork, networkCoordinator.network, new EventsManagerImpl(), scenario.getTransitVehicles, fareCalculator, tollCalculator))

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
      val leg = BeamLeg(3000, BeamMode.CAR, 0, BeamPath(Vector(143, 60, 58, 62, 80, 74, 68, 154), None, SpaceTime(166321.9, 1568.87, 3000), SpaceTime(167138.4, 1117, 3000), 0.0))
      router ! EmbodyWithCurrentTravelTime(leg, Id.createVehicleId(1))
      val response = expectMsgType[RoutingResponse]
      assert(response.itineraries.head.beamLegs().head.duration == 70)
      // R5 travel time, but less than what's in R5's routing response (see vv),
      // presumably because the first/last edge are not travelled (in R5, trip starts on a "split")
    }

    "take given link traversal times into account" in {
      router ! RoutingRequest(origin, destination, time, Vector(), Vector(StreetVehicle(Id.createVehicleId("car"), new SpaceTime(new Coord(origin.getX, origin.getY), time.atTime), Modes.BeamMode.CAR, asDriver = true)))
      val response = expectMsgType[RoutingResponse]
      assert(response.itineraries.exists(_.tripClassifier == CAR))
      val carOption = response.itineraries.find(_.tripClassifier == CAR).get
      assert(carOption.totalTravelTime == 76)

      router ! UpdateTravelTime((_: Link, _: Double, _: Person, _: Vehicle) => 0) // Nice, we can teleport!
      router ! RoutingRequest(origin, destination, time, Vector(), Vector(StreetVehicle(Id.createVehicleId("car"), new SpaceTime(new Coord(origin.getX, origin.getY), time.atTime), Modes.BeamMode.CAR, asDriver = true)))
      val response2 = expectMsgType[RoutingResponse]
      assert(response2.itineraries.exists(_.tripClassifier == CAR))
      val carOption2 = response2.itineraries.find(_.tripClassifier == CAR).get
      assert(carOption2.totalTravelTime < 7) // isn't exactly 0, probably rounding errors?

      router ! UpdateTravelTime((_: Link, _: Double, _: Person, _: Vehicle) => 1000) // Every link takes 1000 sec to traverse.
      router ! RoutingRequest(origin, destination, time, Vector(), Vector(StreetVehicle(Id.createVehicleId("car"), new SpaceTime(new Coord(origin.getX, origin.getY), time.atTime), Modes.BeamMode.CAR, asDriver = true)))
      val response3 = expectMsgType[RoutingResponse]
      assert(response3.itineraries.exists(_.tripClassifier == CAR))
      val carOption3 = response3.itineraries.find(_.tripClassifier == CAR).get
      assert(carOption3.totalTravelTime < 2071) // isn't exactly 2000, probably rounding errors?
    }

    "find an equilibrium between my estimation and my experience when I report my self-decided link travel times back to it" in {
      // Start with travel times as calculated by a pristine TravelTimeCalculator.
      // (Should be MATSim free flow travel times)
      val eventsForTravelTimeCalculator = EventsUtils.createEventsManager()
      val travelTimeCalculator = new TravelTimeCalculator(networkCoordinator.network, ConfigUtils.createConfig().travelTimeCalculator())
      eventsForTravelTimeCalculator.addHandler(travelTimeCalculator)
      router ! UpdateTravelTime(travelTimeCalculator.getLinkTravelTimes)
      val vehicleId = Id.createVehicleId("car")
      router ! RoutingRequest(origin, destination, time, Vector(), Vector(StreetVehicle(vehicleId, new SpaceTime(new Coord(origin.getX, origin.getY), time.atTime), Modes.BeamMode.CAR, asDriver = true)))
      var carOption = expectMsgType[RoutingResponse].itineraries.find(_.tripClassifier == CAR).get

      // Now feed the TravelTimeCalculator events resulting from me traversing the proposed route,
      // but taking me 2000s (a lot) for each link.
      // Then route again.
      // Like a one-person iterated dynamic traffic assignment.
      def estimatedTotalTravelTime = carOption.totalTravelTime
      def longerTravelTimes(enterTime: Long, linkId: Int) = 2000
      def experiencedTotalTravelTime = (carOption.legs(0).beamLeg.travelPath.linkIds.size - 2) * 2000
      // This ^^ is the travel time which I am now reporting to the TravelTimeCalculator, 2000 per fully-traversed link
      def gap = estimatedTotalTravelTime - experiencedTotalTravelTime

      for (i <- 1 to 5) {
        RoutingModel.traverseStreetLeg(carOption.legs(0).beamLeg, vehicleId, longerTravelTimes).foreach(eventsForTravelTimeCalculator.processEvent)

        // Now send the router the travel times resulting from that, and try again.
        router ! UpdateTravelTime(travelTimeCalculator.getLinkTravelTimes)
        router ! RoutingRequest(origin, destination, time, Vector(), Vector(StreetVehicle(Id.createVehicleId("car"), new SpaceTime(new Coord(origin.getX, origin.getY), time.atTime), Modes.BeamMode.CAR, asDriver = true)))
        carOption = expectMsgType[RoutingResponse].itineraries.find(_.tripClassifier == CAR).get
      }

      assert(scala.math.abs(gap) < 71) // isn't exactly 0, probably rounding errors?
    }

    "give updated travel times for a given route after travel times were updated" in {
      router ! UpdateTravelTime((_: Link, _: Double, _: Person, _: Vehicle) => 1000) // Every link takes 1000 sec to traverse.
      val leg = BeamLeg(28800, BeamMode.WALK, 0, BeamPath(Vector(1, 2, 3, 4), None, SpaceTime(0.0, 0.0, 28800), SpaceTime(1.0, 1.0, 28900), 1000.0))
      router ! EmbodyWithCurrentTravelTime(leg, Id.createVehicleId(1))
      val response = expectMsgType[RoutingResponse]
      assert(response.itineraries.head.beamLegs().head.duration == 2000) // Contains two full links (excluding 1 and 4)
    }


  }

  override def afterAll: Unit = {
    shutdown()
  }

}
