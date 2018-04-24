package beam.performance

import java.time.ZonedDateTime

import akka.actor._
import akka.testkit.{ImplicitSender, TestKit}
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter._
import beam.router.gtfs.FareCalculator
import beam.router.gtfs.FareCalculator.BeamFareSegment
import beam.router.osm.TollCalculator
import beam.router.r5.NetworkCoordinator
import beam.router.{BeamRouter, Modes, RoutingModel}
import beam.sim.BeamServices
import beam.sim.common.{GeoUtils, GeoUtilsImpl}
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.metrics.MetricsSupport
import beam.tags.Performance
import beam.utils.{BeamConfigUtils, DateUtils}
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.network.{Network, Node}
import org.matsim.api.core.v01.population.{Activity, Plan}
import org.matsim.api.core.v01.{Id, Scenario}
import org.matsim.core.events.EventsManagerImpl
import org.matsim.core.router.{Dijkstra, DijkstraFactory}
import org.matsim.vehicles.{VehicleType, VehicleUtils}
import org.mockito.Matchers.any
import org.mockito.Mockito._
import org.scalatest._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.Ignore

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.language.postfixOps
import com.typesafe.config.Config
import org.matsim.core.config.groups.PlanCalcScoreConfigGroup
import org.matsim.core.router.Dijkstra
import org.matsim.core.router.costcalculators.FreespeedTravelTimeAndDisutility
import org.matsim.core.router.util.LeastCostPathCalculator
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.Scenario
import org.matsim.core.network.io.MatsimNetworkReader
import org.matsim.core.router.util.LeastCostPathCalculator
import org.matsim.core.scenario.ScenarioUtils

@Ignore
class RouterPerformanceSpec extends TestKit(ActorSystem("router-test", ConfigFactory.parseString(
  """
  akka.loglevel="OFF"
  akka.test.timefactor=10
  """))) with WordSpecLike with Matchers with Inside with LoneElement
  with ImplicitSender with MockitoSugar with BeforeAndAfterAllConfigMap with MetricsSupport {

  var config: Config = _

  override def beforeAll(configMap: ConfigMap): Unit = {
    val confPath = configMap.getWithDefault("config", "test/input/sf-light/sf-light-25k.conf")

    config = BeamConfigUtils.parseFileSubstitutingInputDirectory(confPath).resolve()


  }

  override def afterAll(configMap: ConfigMap): Unit = {
    shutdown()
    //    if (isMetricsEnable()) Kamon.shutdown()
  }

  "An R5 router" must {

    "respond with a car route for each trip" taggedAs (Performance) in {

      val n = 100000

      //--------------------------------------------
      val beamConfig = BeamConfig(config)
      //    level = beamConfig.beam.metrics.level
      //    if (isMetricsEnable()) Kamon.start(config.withFallback(ConfigFactory.defaultReference()))
      // Have to mock some things to get the router going
      val services: BeamServices = mock[BeamServices]
      when(services.beamConfig).thenReturn(beamConfig)
      val geo = new GeoUtilsImpl(services)
      when(services.geo).thenReturn(geo)
      when(services.dates).thenReturn(DateUtils(ZonedDateTime.parse(beamConfig.beam.routing.baseDate).toLocalDateTime, ZonedDateTime.parse(beamConfig.beam.routing.baseDate)))
      val networkCoordinator: NetworkCoordinator = new NetworkCoordinator(beamConfig, VehicleUtils.createVehiclesContainer())
      networkCoordinator.loadNetwork()

      val fareCalculator = mock[FareCalculator]
      when(fareCalculator.getFareSegments(any(), any(), any(), any(), any())).thenReturn(Vector[BeamFareSegment]())
      val tollCalculator = mock[TollCalculator]
      when(tollCalculator.calcToll(any())).thenReturn(0.0)
      val matsimConfig = new MatSimBeamConfigBuilder(config).buildMatSamConf()
      val scenario = ScenarioUtils.loadScenario(matsimConfig)
      val router = system.actorOf(BeamRouter.props(services, networkCoordinator.transportNetwork, networkCoordinator.network, new EventsManagerImpl(), scenario.getTransitVehicles, fareCalculator, tollCalculator))

      within(60 seconds) { // Router can take a while to initialize
        router ! Identify(0)
        expectMsgType[ActorIdentity]
      }
      //--------------------------------------------

      val testSet = getR5Dataset(scenario, n)
      val start = System.currentTimeMillis()
      try {
        testSet.foreach(pair => {
          val origin = pair(0).getCoord
          val destination = pair(1).getCoord
          val time = RoutingModel.DiscreteTime(pair(0).getEndTime.toInt)
          router ! RoutingRequest(origin, destination, time, Vector(), Vector(
            StreetVehicle(Id.createVehicleId("116378-2"), new SpaceTime(origin, 0), Modes.BeamMode.CAR, asDriver = true)))
          val response = expectMsgType[RoutingResponse]
          assert(response.isInstanceOf[RoutingResponse])

        })
      } finally {
        val latency = System.currentTimeMillis() - start
        println()
        println(s"Time to complete ${testSet.size} requests is : ${latency}ms around ${latency/1000.0}sec")
      }
    }
  }

  "A MATSIM Router" must {

    "respond with a path" in {
      val matsimConfig = new MatSimBeamConfigBuilder(config).buildMatSamConf()
      val scenario = ScenarioUtils.createScenario(matsimConfig)
      val network = scenario.getNetwork
      new MatsimNetworkReader(scenario.getNetwork).readFile("test/input/sf-light/physsim-network.xml")


      val routerAlgo = {
        val travelTimeCostCalculator = new FreespeedTravelTimeAndDisutility(new PlanCalcScoreConfigGroup)

        new DijkstraFactory().createPathCalculator(network, travelTimeCostCalculator, travelTimeCostCalculator)
      //      new Dijkstra(network, travelTimeCostCalculator, travelTimeCostCalculator)
      }

      val start = System.currentTimeMillis();
      val testSet = getDijkstraDataset(network, 100000)
      testSet.foreach({ pare =>
        val path = routerAlgo.calcLeastCostPath(pare(0), pare(1), 8.0 * 3600, null, null)
      })
      val latency = System.currentTimeMillis() - start
      println()
      println(s"Time to complete ${testSet.size} requests is : ${latency}ms around ${latency/1000.0}sec")

//      println(path)
    }
  }

  def getDijkstraDataset(network: Network, n: Int): Seq[Seq[Node]] = {
    val nodes = network.getNodes.values().asScala.toSeq
    (nodes ++ nodes ++ nodes).sliding(2).take(n).toSeq
  }

  def getR5Dataset(scenario: Scenario, n: Int): Seq[Seq[Activity]] = {
    val pers = scenario.getPopulation.getPersons.values().asScala.toSeq
    val data = pers.map(_.getSelectedPlan).flatMap(planToVec).sliding(2).toSeq
    (data ++ data).take(n)
  }

  def getR5Dataset1(scenario: Scenario): Seq[(Activity,Activity)] = {
    val pers = scenario.getPopulation.getPersons.values().asScala.toSeq
    val data = pers.map(_.getSelectedPlan).flatMap(planToVec)
    val data1 = data.take(data.size/2)
    val data2 = data.takeRight(data.size/2 - 1)
    for { x <- data1; y <- data2  if(x != y)} yield (x, y)
    //      data.flatMap(x => data.map(y => if(x!=y) (x,y))).asInstanceOf[Seq[(Activity,Activity)]]
  }

  def planToVec(plan: Plan): Vector[Activity] = {
    Vector.empty[Activity] ++ plan.getPlanElements.asScala
      .filter(_.isInstanceOf[Activity])
      .map(_.asInstanceOf[Activity])
  }
}
