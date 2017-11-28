package beam.sfbay

import java.io.File
import java.time.ZonedDateTime

import akka.actor._
import akka.testkit.{ImplicitSender, TestKit}
import beam.agentsim.agents.vehicles.BeamVehicle.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter._
import beam.router.Modes.BeamMode.{CAR, RIDEHAIL, WALK}
import beam.router.gtfs.FareCalculator
import beam.router.{BeamRouter, Modes, RoutingModel}
import beam.sim.BeamServices
import beam.sim.common.GeoUtilsImpl
import beam.sim.config.BeamConfig
import beam.utils.DateUtils
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.config.ConfigUtils
import org.matsim.core.scenario.ScenarioUtils
import org.mockito.Mockito._
import org.scalatest._
import org.scalatest.mockito.MockitoSugar

import scala.concurrent.duration._
import scala.language.postfixOps

@Ignore
class SfbayRouterSpec extends TestKit(ActorSystem("router-test")) with WordSpecLike with Matchers
  with ImplicitSender with MockitoSugar with BeforeAndAfterAll {

  var router: ActorRef = _

  override def beforeAll: Unit = {
    val beamConfig = BeamConfig(ConfigFactory.parseFile(new File("production/application-sfbay/beam.conf")).resolve())

    // Have to mock some things to get the router going
    val services: BeamServices = mock[BeamServices]
    when(services.beamConfig).thenReturn(beamConfig)
    when(services.geo).thenReturn(new GeoUtilsImpl(services))
    when(services.dates).thenReturn(DateUtils(beamConfig.beam.routing.baseDate,ZonedDateTime.parse(beamConfig.beam.routing.baseDate).toLocalDateTime,ZonedDateTime.parse(beamConfig.beam.routing.baseDate)))

    val fareCalculator = new FareCalculator(beamConfig.beam.routing.r5.directory)
    val scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig())
    router = system.actorOf(BeamRouter.props(services, scenario.getTransitVehicles, fareCalculator))

    within(60 seconds) { // Router can take a while to initialize
      router ! Identify(0)
      expectMsgType[ActorIdentity]
    }
  }

  override def afterAll: Unit = {
    shutdown()
  }

  "A router" must {
    "respond with a route to a first reasonable RoutingRequest" in {
      val origin = new BeamRouter.Location(583152.4334365112, 4139386.503815964)
      val destination = new BeamRouter.Location(572710.8214231567, 4142569.0802786923)
      val time = RoutingModel.DiscreteTime(25740)
      router ! RoutingRequest(RoutingRequestTripInfo(origin, destination, time, Vector(), Vector(StreetVehicle(Id.createVehicleId("body-667520-0"), new SpaceTime(new Coord(origin.getX, origin.getY), time.atTime), Modes.BeamMode.WALK, asDriver = true)), Id.createPersonId("667520-0")))
      val response = expectMsgType[RoutingResponse]
      assert(response.itineraries.exists(_.tripClassifier == WALK))
    }

    "respond with a fallback walk route to a RoutingRequest where walking would take approx. 8 hours" in {
      val origin = new BeamRouter.Location(626575.0322098453, 4181202.599243111)
      val destination = new BeamRouter.Location(607385.7148858022, 4172426.3760835854)
      val time = RoutingModel.DiscreteTime(25860)
      router ! RoutingRequest(RoutingRequestTripInfo(origin, destination, time, Vector(), Vector(StreetVehicle(Id.createVehicleId("body-56658-0"), new SpaceTime(new Coord(origin.getX, origin.getY), time.atTime), Modes.BeamMode.WALK, asDriver = true)), Id.createPersonId("56658-0")))
      val response = expectMsgType[RoutingResponse]
      assert(response.itineraries.exists(_.tripClassifier == WALK))
    }

    "respond with a route to yet another reasonable RoutingRequest" in {
      val origin = new BeamRouter.Location(583117.0300037456, 4168059.6668392466)
      val destination = new BeamRouter.Location(579985.712067158, 4167298.6137483735)
      val time = RoutingModel.DiscreteTime(20460)
      router ! RoutingRequest(RoutingRequestTripInfo(origin, destination, time, Vector(), Vector(StreetVehicle(Id.createVehicleId("body-80672-0"), new SpaceTime(new Coord(origin.getX, origin.getY), time.atTime), Modes.BeamMode.WALK, asDriver = true)), Id.createPersonId("80672-0")))
      val response = expectMsgType[RoutingResponse]
      assert(response.itineraries.exists(_.tripClassifier == WALK))
    }

    "respond with a ride hailing route to a reasonable RoutingRequest" in {
      val origin = new BeamRouter.Location(551642.4729978561, 4180839.138663753)
      val destination = new BeamRouter.Location(552065.6882372601, 4180855.582994787)
      val time = RoutingModel.DiscreteTime(19740)
      router ! RoutingRequest(RoutingRequestTripInfo(origin, destination, time, Vector(), Vector(
        StreetVehicle(Id.createVehicleId("rideHailingVehicle-person=17673-0"), new SpaceTime(new Coord(origin.getX, origin.getY), time.atTime), Modes.BeamMode.CAR, asDriver = false),
        StreetVehicle(Id.createVehicleId("body-17673-0"), new SpaceTime(new Coord(origin.getX, origin.getY), time.atTime), Modes.BeamMode.WALK, asDriver = true),
        StreetVehicle(Id.createVehicleId("17673-0"), new SpaceTime(new Coord(origin.getX, origin.getY), time.atTime), Modes.BeamMode.CAR, asDriver = true)
      ), Id.createPersonId("17673-0")))
      val response = expectMsgType[RoutingResponse]
      assert(response.itineraries.exists(_.tripClassifier == WALK))
      assert(response.itineraries.exists(_.tripClassifier == RIDEHAIL))
      assert(response.itineraries.exists(_.tripClassifier == CAR))
    }

    "respond with a fallback walk route to a RoutingRequest which actually doesn't have a walkable solution, and a car route" in {
      val origin = new BeamRouter.Location(545379.1120515711, 4196841.43220292)
      val destination = new BeamRouter.Location(550620.1726742609, 4201484.428639883)
      val time = RoutingModel.DiscreteTime(27840)
      router ! RoutingRequest(RoutingRequestTripInfo(origin, destination, time, Vector(), Vector(
        StreetVehicle(Id.createVehicleId("116378-2"), new SpaceTime(new Coord(545639.565355, 4196945.53107), 0), Modes.BeamMode.CAR, asDriver = true),
        StreetVehicle(Id.createVehicleId("body-116378-2"), new SpaceTime(new Coord(origin.getX, origin.getY), time.atTime), Modes.BeamMode.WALK, asDriver = true)
      ), Id.createPersonId("116378-2")))
      val response = expectMsgType[RoutingResponse]

      assert(response.itineraries.exists(_.tripClassifier == WALK))
      assert(response.itineraries.exists(_.tripClassifier == CAR))

      val carOption = response.itineraries.find(_.tripClassifier == CAR).get
      assertMakesSense(carOption)
      val actualModesOfCarOption = carOption.toBeamTrip().legs.map(_.mode)
      actualModesOfCarOption should contain theSameElementsInOrderAs List(WALK, CAR, WALK)

      val actualModesOfWalkOption = response.itineraries.find(_.tripClassifier == WALK).get.toBeamTrip().legs.map(_.mode)
      actualModesOfWalkOption should contain theSameElementsInOrderAs List(WALK)
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
