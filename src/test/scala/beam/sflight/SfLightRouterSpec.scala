package beam.sflight

import java.io.File
import java.time.ZonedDateTime

import akka.actor._
import akka.testkit.{ImplicitSender, TestKit}
import beam.agentsim.agents.vehicles.BeamVehicle.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter._
import beam.router.Modes.BeamMode.{CAR, RIDEHAIL, WALK}
import beam.router.RoutingModel.{BeamLeg, BeamPath, BeamTrip}
import beam.router.gtfs.FareCalculator
import beam.router.{BeamRouter, Modes, RoutingModel}
import beam.sim.BeamServices
import beam.sim.common.{GeoUtils, GeoUtilsImpl}
import beam.sim.config.BeamConfig
import beam.utils.DateUtils
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.config.ConfigUtils
import org.matsim.core.events.EventsManagerImpl
import org.matsim.core.scenario.ScenarioUtils
import org.mockito.Mockito._
import org.scalatest._
import org.scalatest.mockito.MockitoSugar

import scala.concurrent.duration._
import scala.language.postfixOps

class SfLightRouterSpec extends TestKit(ActorSystem("router-test")) with WordSpecLike with Matchers with Inside with LoneElement
  with ImplicitSender with MockitoSugar with BeforeAndAfterAll {

  var router: ActorRef = _
  var geo: GeoUtils = _

  override def beforeAll: Unit = {
    val beamConfig = BeamConfig(ConfigFactory.parseFile(new File("test/input/sf-light/sf-light.conf")).resolve())

    // Have to mock some things to get the router going
    val services: BeamServices = mock[BeamServices]
    when(services.beamConfig).thenReturn(beamConfig)
    geo = new GeoUtilsImpl(services)
    when(services.geo).thenReturn(geo)
    when(services.dates).thenReturn(DateUtils(beamConfig.beam.routing.baseDate,ZonedDateTime.parse(beamConfig.beam.routing.baseDate).toLocalDateTime,ZonedDateTime.parse(beamConfig.beam.routing.baseDate)))

    val fareCalculator = new FareCalculator(beamConfig.beam.routing.r5.directory)
    val scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig())
    router = system.actorOf(BeamRouter.props(services, scenario.getNetwork, new EventsManagerImpl(), scenario.getTransitVehicles, fareCalculator))

    within(60 seconds) { // Router can take a while to initialize
      router ! Identify(0)
      expectMsgType[ActorIdentity]
    }
  }

  override def afterAll: Unit = {
    shutdown()
  }

  "A router" must {

    "respond with a car route and a dummy walk route (bay bridge considered unwalkable) for going from downtown SF to Treasure Island" in {
      val origin = geo.wgs2Utm(new Coord(-122.439194, 37.785368))
      val destination = geo.wgs2Utm(new Coord(-122.3712, 37.815819))
      val time = RoutingModel.DiscreteTime(27840)
      router ! RoutingRequest(RoutingRequestTripInfo(origin, destination, time, Vector(), Vector(
        StreetVehicle(Id.createVehicleId("116378-2"), new SpaceTime(origin, 0), Modes.BeamMode.CAR, asDriver = true),
        StreetVehicle(Id.createVehicleId("rideHailingVehicle-person=116378-2"), new SpaceTime(new Coord(origin.getX, origin.getY), time.atTime), Modes.BeamMode.CAR, asDriver = false),
        StreetVehicle(Id.createVehicleId("body-116378-2"), new SpaceTime(new Coord(origin.getX, origin.getY), time.atTime), Modes.BeamMode.WALK, asDriver = true)
      ), Id.createPersonId("116378-2")))
      val response = expectMsgType[RoutingResponse]
      assert(response.itineraries.exists(_.tripClassifier == WALK))
      assert(response.itineraries.exists(_.tripClassifier == RIDEHAIL))
      assert(response.itineraries.exists(_.tripClassifier == CAR))

      val walkTrip = response.itineraries.find(_.tripClassifier == WALK).get.toBeamTrip()
      inside (walkTrip) {
        case BeamTrip(legs, _) =>
          legs.map(_.mode) should contain theSameElementsInOrderAs List(WALK)
          inside (legs.loneElement) {
            case BeamLeg(_, mode, _, BeamPath(links, _, _)) =>
              mode should be (WALK)
              links should be ('empty)
          }
      }
    }

  }

  def assertMakesSense(trip: RoutingModel.EmbodiedBeamTrip): Unit = {
    var time = trip.legs.head.beamLeg.startTime
    trip.legs.foreach(leg => {
      assert(leg.beamLeg.startTime == time, "Leg starts when previous one finishes.")
      time += leg.beamLeg.duration
    })
  }

}
