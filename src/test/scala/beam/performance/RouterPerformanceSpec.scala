package beam.performance

import java.time.ZonedDateTime
import java.util
import java.util.concurrent.ThreadLocalRandom

import akka.actor.Status.Success
import akka.actor._
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.ZonalParkingManagerSpec
import beam.router.BeamRouter._
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{BIKE, BUS, CAR, RIDE_HAIL, TRANSIT, WALK, WALK_TRANSIT}
import beam.router.RoutingModel.WindowTime
import beam.router.gtfs.FareCalculator
import beam.router.osm.TollCalculator
import beam.router.r5.NetworkCoordinator
import beam.router.{BeamRouter, RoutingModel}
import beam.sim.BeamServices
import beam.sim.common.GeoUtilsImpl
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.metrics.MetricsSupport
import beam.tags.Performance
import beam.utils.DateUtils
import beam.utils.TestConfigUtils.testConfig
import com.conveyal.r5.api.util.LegMode
import com.conveyal.r5.profile.ProfileRequest
import com.conveyal.r5.transit.TransportNetwork
import com.typesafe.config.{Config, ConfigFactory}
import org.apache.commons.lang3.reflect.FieldUtils
import org.matsim.api.core.v01.network.{Network, Node}
import org.matsim.api.core.v01.population.{Activity, Plan}
import org.matsim.api.core.v01.{Coord, Id, Scenario, TransportMode}
import org.matsim.core.config.groups.{GlobalConfigGroup, PlanCalcScoreConfigGroup}
import org.matsim.core.events.EventsManagerImpl
import org.matsim.core.router._
import org.matsim.core.router.costcalculators.{
  FreespeedTravelTimeAndDisutility,
  RandomizingTimeDistanceTravelDisutilityFactory
}
import org.matsim.core.router.util.{LeastCostPathCalculator, PreProcessLandmarks}
import org.matsim.core.scenario.ScenarioUtils
import org.matsim.core.trafficmonitoring.FreeSpeedTravelTime
import org.matsim.core.utils.geometry.transformations.GeotoolsTransformation
import org.matsim.vehicles.Vehicle
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito._
import org.scalatest._
import org.scalatest.mockito.MockitoSugar

import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._
import scala.language.postfixOps

@Ignore
class RouterPerformanceSpec
    extends TestKit(
      ActorSystem("RouterPerformanceSpec", ConfigFactory.parseString("""
  akka.loglevel="OFF"
  akka.test.timefactor=10
  """))
    )
    with WordSpecLike
    with Matchers
    with Inside
    with LoneElement
    with ImplicitSender
    with MockitoSugar
    with BeforeAndAfterAllConfigMap
    with MetricsSupport {

  var config: Config = _
  var network: Network = _
  var router: ActorRef = _
  var scenario: Scenario = _

  private val runSet = List(
    1000,
    10000,
    100000
    /*, 10000, 25000, 50000, 75000*/
  )

  var dataSet: Seq[Seq[Node]] = _

  override def beforeAll(configMap: ConfigMap): Unit = {
    val confPath =
      configMap.getWithDefault("config", "test/input/sf-light/sf-light.conf")
    config = testConfig(confPath)
    val beamConfig = BeamConfig(config)

    val services: BeamServices = mock[BeamServices]
    when(services.beamConfig).thenReturn(beamConfig)
    val geo = new GeoUtilsImpl(services)
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

    val fareCalculator = new FareCalculator(beamConfig.beam.routing.r5.directory)
    val tollCalculator = mock[TollCalculator]
    when(tollCalculator.calcToll(any())).thenReturn(0.0)
    val matsimConfig = new MatSimBeamConfigBuilder(config).buildMatSamConf()
    scenario = ScenarioUtils.loadScenario(matsimConfig)
    network = scenario.getNetwork
    router = system.actorOf(
      BeamRouter.props(
        services,
        networkCoordinator.transportNetwork,
        networkCoordinator.network,
        new EventsManagerImpl(),
        scenario.getTransitVehicles,
        fareCalculator,
        tollCalculator
      ),
      "router"
    )
    val zonalParkingManager = ZonalParkingManagerSpec.mockZonalParkingManager(services, Some(router), None)

    within(60 seconds) { // Router can take a while to initialize
      router ! Identify(0)
      expectMsgType[ActorIdentity]
      router ! InitTransit(new TestProbe(system).ref, zonalParkingManager)
      expectMsgType[Success]
    }
    dataSet = getRandomNodePairDataset(runSet.max)
  }

  override def afterAll(configMap: ConfigMap): Unit = {
    shutdown()
    //    if (isMetricsEnable()) Kamon.shutdown()
  }

  "A Beam router" must {

    "respond with a car route for each trip" taggedAs Performance in {

      println("=================BEAM=================")

      //      val dataSet = getR5Dataset(scenario, 100000)
      runSet.foreach(n => {
        val testSet = dataSet.take(n)
        val start = System.currentTimeMillis()
        try testSet.foreach(pair => {
          val origin = pair.head.getCoord
          val destination = pair(1).getCoord

          val time = RoutingModel.DiscreteTime(8 * 3600)
          router ! RoutingRequest(
            origin,
            destination,
            time,
            Vector(),
            Vector(
              StreetVehicle(
                Id.createVehicleId("116378-2"),
                new SpaceTime(origin, 0),
                CAR,
                asDriver = true
              )
            )
          )
          val response = expectMsgType[RoutingResponse]

          //            println("--------------------------------------")
          //            println(s"origin.x:${origin.getX}, origin.y: ${origin.getY}")
          //            println(s"destination.x:${destination.getX}, destination.y: ${destination.getY}")
          //            println(response)
          //            print("links#")
          //            response.itineraries.flatMap(_.beamLegs()).map(_.travelPath.linkIds.size).foreach(print)
          //            response.itineraries.foreach(i => println(s", time:${i.totalTravelTime}"))

          assert(response.isInstanceOf[RoutingResponse])

        })
        finally {
          val latency = System.currentTimeMillis() - start
          println()
          println(
            s"Time to complete ${testSet.size} requests is : ${latency}ms around ${latency / 1000.0}sec"
          )
        }
      })
    }

    "respond with a route for each beam mode" taggedAs Performance in {
      val modeSet: Seq[BeamMode] =
        Seq(CAR, BIKE, WALK, RIDE_HAIL, BUS, WALK_TRANSIT, TRANSIT)

      var transitModes: Vector[BeamMode] = Vector()
      var streetVehicles: Vector[StreetVehicle] = Vector()

      val r5Set = getRandomNodePairDataset(runSet.max)
      modeSet.foreach(mode => {
        println(s"=================${mode.value}=================")
        runSet.foreach(n => {
          val testSet = r5Set.take(n)
          val start = System.currentTimeMillis()
          testSet.foreach(pair => {
            val origin = pair.head.getCoord
            val destination = pair(1).getCoord
            val time =
              RoutingModel.DiscreteTime(8 * 3600 /*pair(0).getEndTime.toInt*/ )

            mode.r5Mode match {
              case Some(Left(_)) =>
                transitModes = Vector()
                streetVehicles = Vector(
                  StreetVehicle(
                    Id.createVehicleId("116378-2"),
                    new SpaceTime(origin, time.atTime),
                    mode,
                    asDriver = true
                  )
                )
              case Some(Right(_)) =>
                transitModes = Vector(mode)
                streetVehicles = Vector(
                  StreetVehicle(
                    Id.createVehicleId("body-116378-2"),
                    new SpaceTime(new Coord(origin.getX, origin.getY), time.atTime),
                    WALK,
                    asDriver = true
                  )
                )

              case None =>
            }
            val response = within(60 second) {
              router ! RoutingRequest(origin, destination, time, transitModes, streetVehicles)
              expectMsgType[RoutingResponse]
            }
//            println("--------------------------------------")
//            response.itineraries.foreach(
//              i =>
//                println(
//                  s"links#${i.beamLegs().map(_.travelPath.linkIds.size).sum}, time:${i.totalTravelTime}"
//              )
//            )
          })
          val latency = System.currentTimeMillis() - start
          println()
          println(
            s"Time to complete ${testSet.size} requests is : ${latency}ms around ${latency / 1000.0}sec"
          )
        })
      })
    }
  }

  //  "A R5 router" must {
  //
  //    "respond with a car route for each trip" in {
  //      //--------------------------------------------
  //
  //
  //      val matsimConfig = new MatSimBeamConfigBuilder(config).buildMatSamConf()
  //      val scenario = ScenarioUtils.loadScenario(matsimConfig)
  //
  //      val beamConfig = BeamConfig(config)
  //      val transportNetwork = TransportNetwork.fromDirectory(Paths.get(beamConfig.beam.routing.r5.directory).toFile)
  //
  //      val pointToPointQuery = new PointToPointQuery(transportNetwork)
  //
  //      val activitySet = getR5Dataset(scenario, 100000).map(p => buildRequest(transportNetwork, p(0), p(1)))
  //      runSet.foreach( n => {
  //        val testSet = activitySet.take(n)
  //        val start = System.currentTimeMillis()
  //        try {
  //          testSet.foreach(req => {
  //            val plan = pointToPointQuery.getPlan(req)
  //            if(plan.options.size() > 0) {
  //              println(plan)
  //            }
  //
  //          })
  //        } finally {
  //          val latency = System.currentTimeMillis() - start
  //          println()
  //          println(s"Time to complete ${testSet.size} requests is : ${latency}ms around ${latency / 1000.0}sec")
  //        }
  //      })
  //    }
  //  }

  "A MATSIM Router" must {

    "respond with a path using router alog(AStarEuclidean)" taggedAs Performance in {
      println("=================AStarEuclidean=================")

      testMatsim(getAStarEuclidean)
    }

    "respond with a path using router alog(FastAStarEuclidean)" taggedAs Performance in {
      println("=================FastAStarEuclidean=================")

      testMatsim(getFastAStarEuclidean)
    }

    "respond with a path using router alog(Dijkstra)" taggedAs Performance in {
      println("=================Dijkstra=================")

      testMatsim(getDijkstra)
    }

    "respond with a path using router alog(FastDijkstra)" taggedAs Performance in {
      println("=================FastDijkstra=================")

      testMatsim(getFastDijkstra)
    }

    "respond with a path using router alog(MultiNodeDijkstra)" taggedAs Performance in {
      println("=================MultiNodeDijkstra=================")

      testMatsim(getMultiNodeDijkstra)
    }

    "respond with a path using router alog(FastMultiNodeDijkstra)" taggedAs Performance in {
      println("=================FastMultiNodeDijkstra=================")

      testMatsim(getFastMultiNodeDijkstra)
    }

    "respond with a path using router alog(AStarLandmarks)" taggedAs Performance in {
      println("=================AStarLandmarks=================")

      testMatsim(getAStarLandmarks)
    }

    "respond with a path using router alog(FastAStarLandmarks)" taggedAs Performance in {
      println("=================FastAStarLandmarks=================")

      testMatsim(getFastAStarLandmarks)
    }
  }

  def testMatsim(routerAlgo: LeastCostPathCalculator) {

    runSet.foreach(n => {
      val testSet = dataSet.take(n)
      val start = System.currentTimeMillis()
      testSet.foreach({ pare =>
        val path = routerAlgo.calcLeastCostPath(pare.head, pare(1), 8.0 * 3600, null, null)
      //        println("--------------------------------------")
      //        println(s"origin.x:${pare(0).getCoord.getX}, origin.y: ${pare(0).getCoord.getY}")
      //        println(s"destination.x:${pare(1).getCoord.getX}, destination.y: ${pare(1).getCoord.getY}")
      //        println(s"links#${path.links.size()}, nodes#${path.nodes.size()}, time:${path.travelTime}")
      })
      val latency = System.currentTimeMillis() - start
      println()
      println(
        s"Time to complete ${testSet.size} requests is : ${latency}ms around ${latency / 1000.0}sec"
      )
    })
  }

  def getMultiNodeDijkstra: LeastCostPathCalculator = {
    val matsimConfig = new MatSimBeamConfigBuilder(config).buildMatSamConf()
    val travelTime = new FreeSpeedTravelTime
    val travelDisutility = new RandomizingTimeDistanceTravelDisutilityFactory(
      TransportMode.car,
      matsimConfig.planCalcScore
    ).createTravelDisutility(travelTime)

    new MultiNodeDijkstraFactory()
      .createPathCalculator(network, travelDisutility, travelTime)
  }

  def getFastMultiNodeDijkstra: LeastCostPathCalculator = {
    val matsimConfig = new MatSimBeamConfigBuilder(config).buildMatSamConf()
    val travelTime = new FreeSpeedTravelTime
    val travelDisutility = new RandomizingTimeDistanceTravelDisutilityFactory(
      TransportMode.car,
      matsimConfig.planCalcScore
    ).createTravelDisutility(travelTime)
    new FastMultiNodeDijkstraFactory()
      .createPathCalculator(network, travelDisutility, travelTime)
      .asInstanceOf[FastMultiNodeDijkstra]
  }

  def getAStarEuclidean: LeastCostPathCalculator = {
    val travelTimeCostCalculator = new FreespeedTravelTimeAndDisutility(
      new PlanCalcScoreConfigGroup
    )
    new AStarEuclideanFactory()
      .createPathCalculator(network, travelTimeCostCalculator, travelTimeCostCalculator)
  }

  def getFastAStarEuclidean: LeastCostPathCalculator = {
    val travelTimeCostCalculator = new FreespeedTravelTimeAndDisutility(
      new PlanCalcScoreConfigGroup
    )
    new FastAStarEuclideanFactory()
      .createPathCalculator(network, travelTimeCostCalculator, travelTimeCostCalculator)
  }

  def getAStarLandmarks: LeastCostPathCalculator = {
    val travelTimeCostCalculator = new FreespeedTravelTimeAndDisutility(
      new PlanCalcScoreConfigGroup
    )
    val preProcessData = new PreProcessLandmarks(travelTimeCostCalculator)
    preProcessData.run(network)

    val globalConfig: GlobalConfigGroup = new GlobalConfigGroup()
    val f = new AStarLandmarksFactory(); //injector.getInstance(classOf[AStarLandmarksFactory])//
    FieldUtils.writeField(f, "globalConfig", globalConfig, true)
    f.createPathCalculator(network, travelTimeCostCalculator, travelTimeCostCalculator)
  }

  def getFastAStarLandmarks: LeastCostPathCalculator = {
    val travelTimeCostCalculator = new FreespeedTravelTimeAndDisutility(
      new PlanCalcScoreConfigGroup
    )
    val preProcessData = new PreProcessLandmarks(travelTimeCostCalculator)
    preProcessData.run(network)

    val globalConfig: GlobalConfigGroup = new GlobalConfigGroup()
    val f = new FastAStarLandmarksFactory(); //injector.getInstance(classOf[AStarLandmarksFactory])//
    FieldUtils.writeField(f, "globalConfig", globalConfig, true)
    f.createPathCalculator(network, travelTimeCostCalculator, travelTimeCostCalculator)
  }

  def getDijkstra: LeastCostPathCalculator = {
    val travelTimeCostCalculator = new FreespeedTravelTimeAndDisutility(
      new PlanCalcScoreConfigGroup
    )
    new DijkstraFactory()
      .createPathCalculator(network, travelTimeCostCalculator, travelTimeCostCalculator)
  }

  def getFastDijkstra: LeastCostPathCalculator = {
    val travelTimeCostCalculator = new FreespeedTravelTimeAndDisutility(
      new PlanCalcScoreConfigGroup
    )
    new FastDijkstraFactory()
      .createPathCalculator(network, travelTimeCostCalculator, travelTimeCostCalculator)
  }

  def getNodePairDataset(n: Int): Seq[Seq[Node]] = {
    val nodes = network.getNodes.values().asScala.toSeq
    (nodes ++ nodes ++ nodes).sliding(2).take(n).toSeq
  }

  def getR5Dataset1(scenario: Scenario): Seq[(Activity, Activity)] = {
    val pers = scenario.getPopulation.getPersons.values().asScala.toSeq
    val data = pers.map(_.getSelectedPlan).flatMap(planToVec)
    val data1 = data.take(data.size / 2)
    val data2 = data.takeRight(data.size / 2 - 1)
    for { x <- data1; y <- data2 if x != y } yield (x, y)
    //      data.flatMap(x => data.map(y => if(x!=y) (x,y))).asInstanceOf[Seq[(Activity,Activity)]]
  }

  def planToVec(plan: Plan): Vector[Activity] = {
    Vector.empty[Activity] ++ plan.getPlanElements.asScala
      .filter(_.isInstanceOf[Activity])
      .map(_.asInstanceOf[Activity])
  }

  def getRandomNodePairDataset(n: Int): Seq[Seq[Node]] = {
    val nodes = network.getNodes.values().asScala.toSeq
    for (_ <- 1 to n) yield getRandomNodePair(nodes)
  }

  def getActivityDataset(n: Int): Seq[Seq[Activity]] = {
    val baseDataset = scenario.getPopulation.getPersons
      .values()
      .asScala
      .flatten(person => {
        val activities = planToVec(person.getSelectedPlan)
        activities.sliding(2)
      })
    Seq.fill((n / baseDataset.size) + 1)(baseDataset).flatten
  }

  def getRandomNodePair(nodes: Seq[Node]): Seq[Node] = {
    val total = nodes.length
    val start = ThreadLocalRandom.current().nextInt(0, total)
    var end = ThreadLocalRandom.current().nextInt(0, total)

    while (start == end) {
      end = ThreadLocalRandom.current().nextInt(0, total)
    }

    Seq(nodes(start), nodes(end))
  }

  private lazy val utm2Wgs: GeotoolsTransformation =
    new GeotoolsTransformation("EPSG:26910", "EPSG:4326")

  def Utm2Wgs(coord: Coord): Coord = {
    if (coord.getX > 400.0 | coord.getX < -400.0) {
      utm2Wgs.transform(coord)
    } else {
      coord
    }
  }

  def buildRequest(
    transportNetwork: TransportNetwork,
    fromFacility: Activity,
    toFacility: Activity
  ): ProfileRequest = {
    val profileRequest = new ProfileRequest()
    //Set timezone to timezone of transport network
    profileRequest.zoneId = transportNetwork.getTimeZone

    val origin = Utm2Wgs(fromFacility.getCoord)
    val destination = Utm2Wgs(toFacility.getCoord)

    profileRequest.fromLat = origin.getX
    profileRequest.fromLon = origin.getY
    profileRequest.toLat = destination.getX
    profileRequest.toLon = destination.getY

    //setTime("2015-02-05T07:30+05:00", "2015-02-05T10:30+05:00")
    val time = WindowTime(fromFacility.getEndTime.toInt)
    profileRequest.fromTime = time.fromTime
    profileRequest.toTime = time.toTime

    profileRequest.directModes = util.EnumSet.copyOf(List(LegMode.CAR).asJavaCollection)

    profileRequest
  }
}
