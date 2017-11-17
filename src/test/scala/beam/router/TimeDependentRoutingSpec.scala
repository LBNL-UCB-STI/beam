package beam.router

import java.io.File
import java.time.ZonedDateTime

import akka.actor.{ActorIdentity, ActorRef, ActorSystem, Identify}
import akka.testkit.{ImplicitSender, TestKit}
import beam.agentsim.agents.vehicles.BeamVehicle.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter._
import beam.router.Modes.BeamMode.WALK
import beam.router.RoutingModel.BeamLegWithNext
import beam.router.gtfs.FareCalculator
import beam.sim.BeamServices
import beam.sim.common.GeoUtilsImpl
import beam.sim.config.BeamConfig
import beam.utils.DateUtils
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.config.ConfigUtils
import org.matsim.core.controler.MatsimServices
import org.matsim.core.scenario.ScenarioUtils
import org.matsim.vehicles.Vehicle
import org.mockito.Mockito.when
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._
import scala.language.postfixOps

class TimeDependentRoutingSpec extends TestKit(ActorSystem("router-test")) with WordSpecLike with Matchers
  with ImplicitSender with MockitoSugar with BeforeAndAfterAll {

  var router: ActorRef = _

  override def beforeAll: Unit = {
    val beamConfig = BeamConfig(ConfigFactory.parseFile(new File("test/input/beamville/beam.conf")).resolve())

    // Have to mock a lot of things to get the router going
    val services: BeamServices = mock[BeamServices]
    val scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig())
    when(services.beamConfig).thenReturn(beamConfig)
    when(services.geo).thenReturn(new GeoUtilsImpl(services))
    val matsimServices = mock[MatsimServices]
    when(matsimServices.getScenario).thenReturn(scenario)
    when(services.matsimServices).thenReturn(matsimServices)
    when(services.dates).thenReturn(DateUtils(beamConfig.beam.routing.baseDate,ZonedDateTime.parse(beamConfig.beam.routing.baseDate).toLocalDateTime,ZonedDateTime.parse(beamConfig.beam.routing.baseDate)))
    val tupleToNext = new TrieMap[Tuple3[Int, Int, Long],BeamLegWithNext]

    val fareCalculator = new FareCalculator(beamConfig.beam.routing.r5.directory)
    router = system.actorOf(BeamRouter.props(services, scenario.getTransitVehicles, fareCalculator))

    within(60 seconds) { // Router can take a while to initialize
      router ! Identify(0)
      expectMsgType[ActorIdentity]
    }
  }

  "A time-dependent router" must {
    "take given link traversal times into account" in {
      val origin = new BeamRouter.Location(166321.9, 1568.87)
      val destination = new BeamRouter.Location(167138.4, 1117)
      val time = RoutingModel.DiscreteTime(0)
      router ! RoutingRequest(RoutingRequestTripInfo(origin, destination, time, Vector(), Vector(StreetVehicle(Id.createVehicleId("body-667520-0"), new SpaceTime(new Coord(origin.getX, origin.getY), time.atTime), Modes.BeamMode.WALK, asDriver = true)), Id.createPersonId("667520-0")))
      val response = expectMsgType[RoutingResponse]
      assert(response.itineraries.exists(_.tripClassifier == WALK))
      val walkOption = response.itineraries.find(_.tripClassifier == WALK).get
      assert(walkOption.totalTravelTime == 860)

      router ! UpdateTravelTime((_: Link, _: Double, _: Person, _: Vehicle) => 0) // Nice, we can teleport!
      router ! RoutingRequest(RoutingRequestTripInfo(origin, destination, time, Vector(), Vector(StreetVehicle(Id.createVehicleId("body-667520-0"), new SpaceTime(new Coord(origin.getX, origin.getY), time.atTime), Modes.BeamMode.WALK, asDriver = true)), Id.createPersonId("667520-0")))
      val response2 = expectMsgType[RoutingResponse]
      assert(response2.itineraries.exists(_.tripClassifier == WALK))
      val walkOption2 = response2.itineraries.find(_.tripClassifier == WALK).get
      assert(walkOption2.totalTravelTime < 10) // isn't exactly 0, probably rounding issues

      router ! UpdateTravelTime((_: Link, _: Double, _: Person, _: Vehicle) => 1000) // Every link takes 1000 sec to traverse.
      router ! RoutingRequest(RoutingRequestTripInfo(origin, destination, time, Vector(), Vector(StreetVehicle(Id.createVehicleId("body-667520-0"), new SpaceTime(new Coord(origin.getX, origin.getY), time.atTime), Modes.BeamMode.WALK, asDriver = true)), Id.createPersonId("667520-0")))
      val response3 = expectMsgType[RoutingResponse]
      assert(response3.itineraries.exists(_.tripClassifier == WALK))
      val walkOption3 = response3.itineraries.find(_.tripClassifier == WALK).get
      assert(walkOption3.totalTravelTime < 2010) // isn't exactly 2000, probably rounding issues
    }
  }

}
