package beam.sfbay

import java.io.File
import java.time.ZonedDateTime

import akka.actor.Status.Success
import akka.actor._
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import beam.agentsim.agents.vehicles.BeamVehicle.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter._
import beam.router.Modes.BeamMode.{TRANSIT, WALK, WALK_TRANSIT}
import beam.router.gtfs.FareCalculator
import beam.router.{BeamRouter, Modes, RoutingModel}
import beam.sim.BeamServices
import beam.sim.common.{GeoUtils, GeoUtilsImpl}
import beam.sim.config.BeamConfig
import beam.utils.DateUtils
import com.typesafe.config.{ConfigFactory, ConfigParseOptions}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.config.ConfigUtils
import org.matsim.core.events.EventsManagerImpl
import org.matsim.core.scenario.ScenarioUtils
import org.matsim.vehicles.{Vehicle, VehicleReaderV1}
import org.mockito.Mockito.when
import org.scalatest._
import org.scalatest.mockito.MockitoSugar

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._
import scala.language.postfixOps

class SfbayRouterTransitSpec extends TestKit(ActorSystem("router-test")) with WordSpecLike with Matchers
  with ImplicitSender with MockitoSugar with BeforeAndAfterAll {

  var router: ActorRef = _
  var geo: GeoUtils = _

  override def beforeAll: Unit = {
    val beamConfig = BeamConfig(ConfigFactory.parseFile(
      new File("test/input/sf-light/sf-light.conf"),
      ConfigParseOptions.defaults().setAllowMissing(false)).resolve())

    // Have to mock a lot of things to get the router going
    val services: BeamServices = mock[BeamServices]
    when(services.beamConfig).thenReturn(beamConfig)
    geo = new GeoUtilsImpl(services)
    when(services.geo).thenReturn(geo)
    when(services.dates).thenReturn(DateUtils(beamConfig.beam.routing.baseDate,ZonedDateTime.parse(beamConfig.beam.routing.baseDate).toLocalDateTime,ZonedDateTime.parse(beamConfig.beam.routing.baseDate)))
    when(services.vehicles).thenReturn(new TrieMap[Id[Vehicle], Vehicle])
    when(services.vehicleRefs).thenReturn(new TrieMap[Id[Vehicle], ActorRef])
    when(services.agentRefs).thenReturn(new TrieMap[String, ActorRef])
    when(services.schedulerRef).thenReturn(TestProbe("scheduler").ref)

    val fareCalculator = new FareCalculator(beamConfig.beam.routing.r5.directory)
    val scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig())
    new VehicleReaderV1(scenario.getTransitVehicles).readFile("test/input/sf-light/transitVehicles.xml")
    router = system.actorOf(BeamRouter.props(services, scenario.getNetwork, new EventsManagerImpl(), scenario.getTransitVehicles, fareCalculator), "router")

    within(5 minutes) { // Router can take a while to initialize
      router ! Identify(0)
      expectMsgType[ActorIdentity]
      router ! InitTransit
      expectMsgType[Success]
    }
  }

  override def afterAll: Unit = {
    shutdown()
  }

  "A router" must {
    "respond with a route to a first reasonable RoutingRequest" in {
      val origin = geo.wgs2Utm(new Coord(-122.396944, 37.79288)) // Embarcadero
      val destination = geo.wgs2Utm(new Coord(-122.460555, 37.764294)) // Near UCSF medical center
      val time = RoutingModel.DiscreteTime(25740)
      router ! RoutingRequest(RoutingRequestTripInfo(origin, destination, time, Vector(Modes.BeamMode.WALK_TRANSIT), Vector(StreetVehicle(Id.createVehicleId("body-667520-0"), new SpaceTime(origin, time.atTime), Modes.BeamMode.WALK, asDriver = true)), Id.createPersonId("667520-0")))
      val response = expectMsgType[RoutingResponse]
      assert(response.itineraries.exists(_.tripClassifier == WALK))
      assert(response.itineraries.exists(_.tripClassifier == WALK_TRANSIT))
      val transitOption = response.itineraries.find(_.tripClassifier == WALK_TRANSIT).get
      assertMakesSense(transitOption)
      assert(transitOption.legs.head.beamLeg.startTime == 26031)
    }
  }

  def assertMakesSense(trip: RoutingModel.EmbodiedBeamTrip): Unit = {
    var time = trip.legs.head.beamLeg.startTime
    trip.legs.foreach(leg => {
      assert(leg.beamLeg.startTime >= time, "Leg starts when or after previous one finishes.")
      time += leg.beamLeg.duration
    })
  }

}
