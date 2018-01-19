package beam.sflight

import java.time.ZonedDateTime

import akka.actor.Status.Success
import akka.actor._
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import beam.agentsim.agents.PersonAgent
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter._
import beam.router.Modes.BeamMode.{DRIVE_TRANSIT, WALK, WALK_TRANSIT}
import beam.router.gtfs.FareCalculator
import beam.router.gtfs.FareCalculator.BeamFareSegment
import beam.router.osm.TollCalculator
import beam.router.r5.NetworkCoordinator
import beam.router.{BeamRouter, Modes, RoutingModel}
import beam.sim.BeamServices
import beam.sim.common.{GeoUtils, GeoUtilsImpl}
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.utils.{BeamConfigUtils, DateUtils}
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.{Coord, Id, Scenario}
import org.matsim.core.events.EventsManagerImpl
import org.matsim.core.scenario.ScenarioUtils
import org.matsim.vehicles.{Vehicle, VehicleUtils}
import org.mockito.Matchers.any
import org.mockito.Mockito.when
import org.scalatest._
import org.scalatest.mockito.MockitoSugar

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._
import scala.language.postfixOps

class SfLightRouterTransitSpec extends TestKit(ActorSystem("router-test", ConfigFactory.parseString(
  """
  akka.loglevel="OFF"
  akka.test.timefactor=10
  """))) with WordSpecLike with Matchers
  with ImplicitSender with MockitoSugar with BeforeAndAfterAll with Inside {

  var router: ActorRef = _
  var geo: GeoUtils = _
  var scenario: Scenario = _

  override def beforeAll: Unit = {
    val config = BeamConfigUtils.parseFileSubstitutingInputDirectory("test/input/sf-light/sf-light.conf").resolve()
    val beamConfig = BeamConfig(config)

    // Have to mock a lot of things to get the router going
    val services: BeamServices = mock[BeamServices]
    when(services.beamConfig).thenReturn(beamConfig)
    geo = new GeoUtilsImpl(services)
    when(services.geo).thenReturn(geo)
    when(services.dates).thenReturn(DateUtils(ZonedDateTime.parse(beamConfig.beam.routing.baseDate).toLocalDateTime, ZonedDateTime.parse(beamConfig.beam.routing.baseDate)))
    when(services.vehicles).thenReturn(new TrieMap[Id[Vehicle], BeamVehicle])
    when(services.agentRefs).thenReturn(new TrieMap[String, ActorRef])
    when(services.schedulerRef).thenReturn(TestProbe("scheduler").ref)
    val networkCoordinator: NetworkCoordinator = new NetworkCoordinator(beamConfig, VehicleUtils.createVehiclesContainer())
    networkCoordinator.loadNetwork()

    val fareCalculator = mock[FareCalculator]
    when(fareCalculator.getFareSegments(any(), any(), any(), any(), any())).thenReturn(Vector[BeamFareSegment]())
    val tollCalculator = mock[TollCalculator]
    when(tollCalculator.calcToll(any())).thenReturn(0.0)
    val matsimConfig = new MatSimBeamConfigBuilder(config).buildMatSamConf()
    scenario = ScenarioUtils.loadScenario(matsimConfig)
    router = system.actorOf(BeamRouter.props(services, networkCoordinator.transportNetwork, networkCoordinator.network, new EventsManagerImpl(), scenario.getTransitVehicles, fareCalculator, tollCalculator), "router")

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
      router ! RoutingRequest(RoutingRequestTripInfo(origin, destination, time, Vector(Modes.BeamMode.WALK_TRANSIT), Vector(StreetVehicle(Id.createVehicleId("body-667520-0"), new SpaceTime(origin, time.atTime), Modes.BeamMode.WALK, asDriver = true))))
      val response = expectMsgType[RoutingResponse]
      assert(response.itineraries.exists(_.tripClassifier == WALK))
      assert(response.itineraries.exists(_.tripClassifier == WALK_TRANSIT))
      val transitOption = response.itineraries.find(_.tripClassifier == WALK_TRANSIT).get
      assertMakesSense(transitOption)
      assert(transitOption.legs.head.beamLeg.startTime == 26004)
    }
  }

  "respond with a drive_transit and a walk_transit route for each trip in sflight" in {
    var totalRoutesCalculated: Int = 0
    var numDriveTransitFound: Int = 0
    var numWalkTransitFound: Int = 0
    scenario.getPopulation.getPersons.values().forEach(person => {
      val activities = PersonAgent.PersonData.planToVec(person.getSelectedPlan)
      activities.sliding(2).foreach(pair => {
        val origin = pair(0).getCoord
        val destination = pair(1).getCoord
        val time = RoutingModel.DiscreteTime(pair(0).getEndTime.toInt)
        router ! RoutingRequest(RoutingRequestTripInfo(origin, destination, time, Vector(Modes.BeamMode.TRANSIT), Vector(
          StreetVehicle(Id.createVehicleId("116378-2"), new SpaceTime(origin, 0), Modes.BeamMode.CAR, asDriver = true),
          StreetVehicle(Id.createVehicleId("body-116378-2"), new SpaceTime(new Coord(origin.getX, origin.getY), time.atTime), Modes.BeamMode.WALK, asDriver = true)
        )))
        val response = expectMsgType[RoutingResponse]
        if (response.itineraries.exists(_.tripClassifier == DRIVE_TRANSIT)) numDriveTransitFound = numDriveTransitFound + 1
        if (response.itineraries.exists(_.tripClassifier == WALK_TRANSIT)) numWalkTransitFound = numWalkTransitFound + 1
        totalRoutesCalculated = totalRoutesCalculated + 1
        //        assert(response.itineraries.exists(_.tripClassifier == DRIVE_TRANSIT))
        //        assert(response.itineraries.exists(_.tripClassifier == WALK_TRANSIT))
      })
    })
    assert(totalRoutesCalculated - numDriveTransitFound < 10)
    assert(totalRoutesCalculated - numWalkTransitFound < 10)
  }

  def assertMakesSense(trip: RoutingModel.EmbodiedBeamTrip): Unit = {
    var time = trip.legs.head.beamLeg.startTime
    trip.legs.foreach(leg => {
      assert(leg.beamLeg.startTime >= time, "Leg starts when or after previous one finishes.")
      time += leg.beamLeg.duration
    })
  }
}
