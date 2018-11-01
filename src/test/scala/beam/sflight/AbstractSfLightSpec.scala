package beam.sflight

import java.time.ZonedDateTime

import akka.actor.{ActorIdentity, ActorRef, ActorSystem, Identify}
import akka.testkit.{ImplicitSender, TestKit}
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.infrastructure.ZonalParkingManagerSpec
import beam.router.BeamRouter
import beam.router.gtfs.FareCalculator
import beam.router.gtfs.FareCalculator.BeamFareSegment
import beam.router.osm.TollCalculator
import beam.router.r5.NetworkCoordinator
import beam.sim.BeamServices
import beam.sim.common.{GeoUtils, GeoUtilsImpl}
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.utils.DateUtils
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.population.{Activity, Plan}
import org.matsim.api.core.v01.{Id, Scenario}
import org.matsim.core.events.EventsManagerImpl
import org.matsim.core.scenario.ScenarioUtils
import org.matsim.vehicles.Vehicle
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import org.mockito.Mockito.when
import org.scalatest._
import org.scalatest.mockito.MockitoSugar

import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._
import scala.language.postfixOps

class AbstractSfLightSpec
    extends TestKit(
      ActorSystem("AbstractSfLightSpec", ConfigFactory.parseString("""
  akka.loglevel="OFF"
  akka.test.timefactor=10
  """))
    )
    with WordSpecLike
    with Matchers
    with ImplicitSender
    with MockitoSugar
    with BeforeAndAfterAll {

  var router: ActorRef = _
  var geo: GeoUtils = _
  var scenario: Scenario = _

  val confPath = "test/input/sf-light/sf-light.conf"
  lazy val config = testConfig(confPath)
  lazy val beamConfig = BeamConfig(config)
  // Have to mock some things to get the router going
  lazy val services: BeamServices = mock[BeamServices]

  override def beforeAll: Unit = {

    when(services.beamConfig).thenReturn(beamConfig)
    geo = new GeoUtilsImpl(services)
    when(services.geo).thenReturn(geo)
    when(services.dates).thenReturn(
      DateUtils(
        ZonedDateTime.parse(beamConfig.beam.routing.baseDate).toLocalDateTime,
        ZonedDateTime.parse(beamConfig.beam.routing.baseDate)
      )
    )
    when(services.vehicles).thenReturn(new TrieMap[Id[BeamVehicle], BeamVehicle])
    val networkCoordinator: NetworkCoordinator = new NetworkCoordinator(beamConfig)
    networkCoordinator.loadNetwork()

    val fareCalculator: FareCalculator = createFareCalc(beamConfig)
    val tollCalculator = new TollCalculator(beamConfig, beamConfig.beam.routing.r5.directory)
    val matsimConfig = new MatSimBeamConfigBuilder(config).buildMatSamConf()
    scenario = ScenarioUtils.loadScenario(matsimConfig)
    router = system.actorOf(
      BeamRouter.props(
        services,
        networkCoordinator.transportNetwork,
        networkCoordinator.network,
        new EventsManagerImpl(),
        scenario.getTransitVehicles,
        fareCalculator,
        tollCalculator
      )
    )

    within(5 minute) { // Router can take a while to initialize
      router ! Identify(0)
      expectMsgType[ActorIdentity]
    }
  }

  override def afterAll: Unit = {
    shutdown()
  }

  def createFareCalc(beamConfig: BeamConfig): FareCalculator = {
    val fareCalculator = mock[FareCalculator]
    when(fareCalculator.getFareSegments(any(), any(), any(), any(), any()))
      .thenReturn(Vector[BeamFareSegment]())
    fareCalculator
  }

  def planToVec(plan: Plan): Vector[Activity] = {
    plan.getPlanElements.asScala
      .filter(_.isInstanceOf[Activity])
      .map(_.asInstanceOf[Activity])
      .toVector
  }
}
