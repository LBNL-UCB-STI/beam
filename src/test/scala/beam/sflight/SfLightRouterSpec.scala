package beam.sflight

import java.time.ZonedDateTime

import akka.actor._
import akka.testkit.{ImplicitSender, TestKit}
import beam.agentsim.agents.PersonAgent
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter._
import beam.router.Modes.BeamMode.{CAR, RIDEHAIL, WALK}
import beam.router.RoutingModel.{BeamLeg, BeamPath, BeamTrip}
import beam.router.gtfs.FareCalculator
import beam.router.gtfs.FareCalculator.BeamFareSegment
import beam.router.osm.TollCalculator
import beam.router.r5.NetworkCoordinator
import beam.router.{BeamRouter, Modes, RoutingModel}
import beam.sim.BeamServices
import beam.sim.common.{GeoUtils, GeoUtilsImpl}
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.utils.{BeamConfigUtils, DateUtils}
import org.matsim.api.core.v01.{Coord, Id, Scenario}
import org.matsim.core.events.EventsManagerImpl
import org.matsim.core.scenario.ScenarioUtils
import org.matsim.vehicles.VehicleUtils
import org.mockito.Matchers.any
import org.mockito.Mockito._
import org.scalatest._
import org.scalatest.mockito.MockitoSugar

import scala.concurrent.duration._
import scala.language.postfixOps

class SfLightRouterSpec extends TestKit(ActorSystem("router-test")) with WordSpecLike with Matchers with Inside with LoneElement
  with ImplicitSender with MockitoSugar with BeforeAndAfterAll {

  var router: ActorRef = _
  var geo: GeoUtils = _
  var scenario: Scenario = _

  override def beforeAll: Unit = {
    val config = BeamConfigUtils.parseFileSubstitutingInputDirectory("test/input/sf-light/sf-light.conf").resolve()
    val beamConfig = BeamConfig(config)

    // Have to mock some things to get the router going
    val services: BeamServices = mock[BeamServices]
    when(services.beamConfig).thenReturn(beamConfig)
    geo = new GeoUtilsImpl(services)
    when(services.geo).thenReturn(geo)
    when(services.dates).thenReturn(DateUtils(ZonedDateTime.parse(beamConfig.beam.routing.baseDate).toLocalDateTime, ZonedDateTime.parse(beamConfig.beam.routing.baseDate)))
    val networkCoordinator: NetworkCoordinator = new NetworkCoordinator(beamConfig, VehicleUtils.createVehiclesContainer())
    networkCoordinator.loadNetwork()

    val fareCalculator = mock[FareCalculator]
    when(fareCalculator.getFareSegments(any(), any(), any(), any(), any())).thenReturn(Vector[BeamFareSegment]())
    val tollCalculator = mock[TollCalculator]
    when(tollCalculator.calcToll(any())).thenReturn(0.0)
    val matsimConfig = new MatSimBeamConfigBuilder(config).buildMatSamConf()
    scenario = ScenarioUtils.loadScenario(matsimConfig)
    router = system.actorOf(BeamRouter.props(services, networkCoordinator.transportNetwork, networkCoordinator.network, new EventsManagerImpl(), scenario.getTransitVehicles, fareCalculator, tollCalculator))

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
      router ! RoutingRequest(RoutingRequestTripInfo(origin, destination, time, Vector(), Vector(StreetVehicle(Id.createVehicleId("body-667520-0"), new SpaceTime(new Coord(origin.getX, origin.getY), time.atTime), Modes.BeamMode.WALK, asDriver = true))))
      val response = expectMsgType[RoutingResponse]
      assert(response.itineraries.exists(_.tripClassifier == WALK))
    }

    "respond with a fallback walk route to a RoutingRequest where walking would take approx. 8 hours" in {
      val origin = new BeamRouter.Location(626575.0322098453, 4181202.599243111)
      val destination = new BeamRouter.Location(607385.7148858022, 4172426.3760835854)
      val time = RoutingModel.DiscreteTime(25860)
      router ! RoutingRequest(RoutingRequestTripInfo(origin, destination, time, Vector(), Vector(StreetVehicle(Id.createVehicleId("body-56658-0"), new SpaceTime(new Coord(origin.getX, origin.getY), time.atTime), Modes.BeamMode.WALK, asDriver = true))))
      val response = expectMsgType[RoutingResponse]
      assert(response.itineraries.exists(_.tripClassifier == WALK))
    }

    "respond with a route to yet another reasonable RoutingRequest" in {
      val origin = new BeamRouter.Location(583117.0300037456, 4168059.6668392466)
      val destination = new BeamRouter.Location(579985.712067158, 4167298.6137483735)
      val time = RoutingModel.DiscreteTime(20460)
      router ! RoutingRequest(RoutingRequestTripInfo(origin, destination, time, Vector(), Vector(StreetVehicle(Id.createVehicleId("body-80672-0"), new SpaceTime(new Coord(origin.getX, origin.getY), time.atTime), Modes.BeamMode.WALK, asDriver = true))))
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
      )))
      val response = expectMsgType[RoutingResponse]
      assert(response.itineraries.exists(_.tripClassifier == WALK))
      assert(response.itineraries.exists(_.tripClassifier == RIDEHAIL))
      assert(response.itineraries.exists(_.tripClassifier == CAR))

      val carOption = response.itineraries.find(_.tripClassifier == CAR).get
      //      assertMakesSense(carOption)
      val actualModesOfCarOption = carOption.toBeamTrip().legs.map(_.mode)
      actualModesOfCarOption should contain theSameElementsInOrderAs List(WALK, CAR, WALK)
    }

    "respond with a walk and a car route for going from downtown SF to Treasure Island" in {
      val origin = geo.wgs2Utm(new Coord(-122.439194, 37.785368))
      val destination = geo.wgs2Utm(new Coord(-122.3712, 37.815819))
      val time = RoutingModel.DiscreteTime(27840)
      router ! RoutingRequest(RoutingRequestTripInfo(origin, destination, time, Vector(), Vector(
        StreetVehicle(Id.createVehicleId("116378-2"), new SpaceTime(origin, 0), Modes.BeamMode.CAR, asDriver = true),
        StreetVehicle(Id.createVehicleId("rideHailingVehicle-person=116378-2"), new SpaceTime(new Coord(origin.getX, origin.getY), time.atTime), Modes.BeamMode.CAR, asDriver = false),
        StreetVehicle(Id.createVehicleId("body-116378-2"), new SpaceTime(new Coord(origin.getX, origin.getY), time.atTime), Modes.BeamMode.WALK, asDriver = true)
      )))
      val response = expectMsgType[RoutingResponse]
      assert(response.itineraries.exists(_.tripClassifier == WALK))
      assert(response.itineraries.exists(_.tripClassifier == RIDEHAIL))
      assert(response.itineraries.exists(_.tripClassifier == CAR))

      val walkTrip = response.itineraries.find(_.tripClassifier == WALK).get.toBeamTrip()
      inside(walkTrip) {
        case BeamTrip(legs, _) =>
          legs.map(_.mode) should contain theSameElementsInOrderAs List(WALK)
          inside(legs.loneElement) {
            case BeamLeg(_, mode, _, BeamPath(links, _, _, _, _)) =>
              mode should be(WALK)
              links should be('empty)
          }
      }
    }

    "respond with a car route and a walk route for each trip in sflight" in {
      scenario.getPopulation.getPersons.values().forEach(person => {
        val activities = PersonAgent.PersonData.planToVec(person.getSelectedPlan)
        activities.sliding(2).foreach(pair => {
          val origin = pair(0).getCoord
          val destination = pair(1).getCoord
          val time = RoutingModel.DiscreteTime(pair(0).getEndTime.toInt)
          router ! RoutingRequest(RoutingRequestTripInfo(origin, destination, time, Vector(), Vector(
            StreetVehicle(Id.createVehicleId("116378-2"), new SpaceTime(origin, 0), Modes.BeamMode.CAR, asDriver = true),
            StreetVehicle(Id.createVehicleId("rideHailingVehicle-person=116378-2"), new SpaceTime(new Coord(origin.getX, origin.getY), time.atTime), Modes.BeamMode.CAR, asDriver = false),
            StreetVehicle(Id.createVehicleId("body-116378-2"), new SpaceTime(new Coord(origin.getX, origin.getY), time.atTime), Modes.BeamMode.WALK, asDriver = true)
          )))
          val response = expectMsgType[RoutingResponse]
          assert(response.itineraries.exists(_.tripClassifier == WALK))
          assert(response.itineraries.exists(_.tripClassifier == RIDEHAIL))
          assert(response.itineraries.exists(_.tripClassifier == CAR))

          val walkTrip = response.itineraries.find(_.tripClassifier == WALK).get.toBeamTrip()
          inside(walkTrip) {
            case BeamTrip(legs, _) =>
              legs.map(_.mode) should contain theSameElementsInOrderAs List(WALK)
              inside(legs.loneElement) {
                case BeamLeg(_, mode, _, BeamPath(links, _, _, _, _)) =>
                  mode should be(WALK)
              }
          }

          val carTrip = response.itineraries.find(_.tripClassifier == CAR).get.toBeamTrip()
          assertMakesSense(carTrip)
          inside(carTrip) {
            case BeamTrip(legs, _) =>
              legs should have size 3
              inside(legs(0)) {
                case BeamLeg(_, mode, _, BeamPath(links, _, _, _, _)) =>
                  mode should be(WALK)
              }
              inside(legs(1)) {
                case BeamLeg(_, mode, _, BeamPath(links, _, _, _, _)) =>
                  mode should be(CAR)
                  links should not be 'empty
              }
              inside(legs(2)) {
                case BeamLeg(_, mode, _, BeamPath(links, _, _, _, _)) =>
                  mode should be(WALK)
              }
          }

        })
      })
    }

  }

  def assertMakesSense(trip: RoutingModel.BeamTrip): Unit = {
    var time = trip.legs.head.startTime
    trip.legs.foreach(leg => {
      assert(leg.startTime == time, "Leg starts when previous one finishes.")
      time += leg.duration
    })
  }

}
