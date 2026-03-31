package beam.router

import akka.actor.Status
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.cluster.{Cluster, MemberStatus}
import akka.cluster.ClusterEvent.UnreachableMember
import akka.pattern.ask
import akka.testkit.{TestKit, TestProbe}
import akka.util.Timeout
import beam.agentsim.agents.choice.mode.PtFares
import beam.agentsim.agents.vehicles.FuelType.Gasoline
import beam.agentsim.agents.vehicles.VehicleCategory.Car
import beam.agentsim.agents.vehicles.{BeamVehicleType, VehicleEmissions}
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.router.BeamRouter._
import beam.router.model.EmbodiedBeamTrip
import beam.sim.BeamScenario
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig
import beam.utils.TestConfigUtils.testConfig
import beam.utils.{DateUtils, NetworkHelper}
import com.conveyal.r5.transit.TransportNetwork
import com.typesafe.config.{Config, ConfigFactory}
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.Network
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.controler.OutputDirectoryHierarchy
import org.matsim.core.utils.collections.QuadTree
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{mock, when}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpecLike

import java.net.ServerSocket
import java.time.ZonedDateTime
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._

class RoutingClusterSpec extends AnyWordSpecLike with Matchers with Eventually with BeforeAndAfterAll {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(20, Seconds), interval = Span(200, Millis))
  implicit val timeout: Timeout = Timeout(30.seconds)

  "RoutingWorker.propsFromConfig" should {
    "boot a worker directly from config" in {
      val config = testConfig("test/input/beamville/beam.conf").resolve()
      val system = ActorSystem("RoutingWorkerFromConfigSpec", config)

      try {
        val worker = system.actorOf(RoutingWorker.propsFromConfig(config))
        val probe = TestProbe()(system)
        worker.tell(RoutingWorker.GetR5Wrapper, probe.ref)
        probe.expectMsgType[beam.router.r5.R5Wrapper](60.seconds)
      } finally {
        TestKit.shutdownActorSystem(system, 60.seconds, verifySystemShutdown = true)
      }
    }
  }

  "BeamRouter distributed routing" should {
    "complete a remote routing request through the cluster worker proxy" in withClusterFixture(
      FakeRemoteWorker.ReplyWithResponse
    ) { fixture =>
      val requester = TestProbe()(fixture.masterSystem)
      fixture.router.tell(sampleRoutingRequest(requestId = 11, triggerId = 101), requester.ref)

      val response = requester.expectMsgType[RoutingResponse](30.seconds)
      response.requestId shouldBe 11
      response.request.map(_.requestId) shouldBe Some(11)
    }

    "fail an outstanding request when the remote worker becomes unreachable" in withClusterFixture(
      FakeRemoteWorker.HoldRequest
    ) { fixture =>
      val requester = TestProbe()(fixture.masterSystem)
      fixture.router.tell(sampleRoutingRequest(requestId = 12, triggerId = 102), requester.ref)

      fixture.workerObserver.expectMsg(FakeRemoteWorker.ReceivedRoutingRequest(12))
      fixture.router ! UnreachableMember(fixture.workerMember)

      val failure = requester.expectMsgType[Status.Failure](30.seconds)
      failure.cause.getMessage should include("Routing worker")
    }

    "return per-worker update acknowledgements for remote travel-time updates" in withClusterFixture(
      FakeRemoteWorker.ReplyWithResponse
    ) { fixture =>
      val requester = TestProbe()(fixture.masterSystem)
      fixture.router.tell(UpdateTravelTimeRemote(new java.util.HashMap[String, Array[Double]]()), requester.ref)

      val results = requester.expectMsgType[UpdateTravelTimeRemoteResults](30.seconds)
      results.results should have size 1
      results.results.head.delivered shouldBe true
      results.results.head.workerPath.get should include("statsServiceProxy")
    }
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
  }

  private case class ClusterFixture(
    masterSystem: ActorSystem,
    workerSystem: ActorSystem,
    router: ActorRef,
    workerObserver: TestProbe,
    workerMember: akka.cluster.Member
  )

  private def withClusterFixture(
    workerBehavior: FakeRemoteWorker.Behavior
  )(testFn: ClusterFixture => Any): Unit = {
    val masterPort = freePort()
    val workerPort = freePort()
    val masterConfig = clusterConfig(masterPort, masterPort, Nil)
    val workerConfig = clusterConfig(workerPort, masterPort, Seq("compute"))
    val masterSystem = ActorSystem("ClusterSystem", masterConfig)
    val workerSystem = ActorSystem("ClusterSystem", workerConfig)
    val workerObserver = TestProbe()(workerSystem)

    try {
      workerSystem.actorOf(Props(new FakeRemoteWorker(workerBehavior, Some(workerObserver.ref))), "statsServiceProxy")

      val masterCluster = Cluster(masterSystem)
      val workerCluster = Cluster(workerSystem)
      masterCluster.join(masterCluster.selfAddress)
      workerCluster.join(masterCluster.selfAddress)

      eventually {
        masterCluster.state.members.count(_.status == MemberStatus.Up) shouldBe 2
        workerCluster.state.members.count(_.status == MemberStatus.Up) shouldBe 2
      }

      val router = masterSystem.actorOf(
        BeamRouter.props(
          beamScenario(masterConfig),
          transportNetworkForDates(masterConfig),
          mock(classOf[Network]),
          mock(classOf[NetworkHelper]),
          mock(classOf[GeoUtils]),
          mock(classOf[beam.router.gtfs.FareCalculator]),
          mock(classOf[beam.router.osm.TollCalculator]),
          mock(classOf[EventsManager]),
          mock(classOf[OutputDirectoryHierarchy])
        )
      )

      eventually {
        val requester = TestProbe()(masterSystem)
        router.tell(UpdateTravelTimeRemote(new java.util.HashMap[String, Array[Double]]()), requester.ref)
        requester
          .expectMsgType[UpdateTravelTimeRemoteResults](10.seconds)
          .results
          .exists(_.workerAddress == workerCluster.selfAddress) shouldBe true
      }

      val member = workerCluster.state.members.find(_.address == workerCluster.selfAddress).get
      testFn(ClusterFixture(masterSystem, workerSystem, router, workerObserver, member))
    } finally {
      TestKit.shutdownActorSystem(workerSystem, 60.seconds, verifySystemShutdown = true)
      TestKit.shutdownActorSystem(masterSystem, 60.seconds, verifySystemShutdown = true)
    }
  }

  private def clusterConfig(port: Int, masterPort: Int, roles: Seq[String]): Config = {
    val rolesConfig =
      if (roles.nonEmpty) s"""akka.cluster.roles = [${roles.map(r => s""""$r"""").mkString(", ")}]""" else ""

    ConfigFactory
      .parseString(s"""
         |beam.cluster.enabled = true
         |beam.useLocalWorker = false
         |akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
         |akka.remote.artery.enabled = on
         |akka.remote.artery.transport = tcp
         |akka.remote.artery.canonical.hostname = "127.0.0.1"
         |akka.remote.artery.canonical.port = $port
         |akka.cluster.seed-nodes = ["akka://ClusterSystem@127.0.0.1:$masterPort"]
         |$rolesConfig
         |akka.log-dead-letters = 0
         |""".stripMargin)
      .withFallback(testConfig("test/input/beamville/beam.conf"))
      .resolve()
  }

  private def beamScenario(config: Config): BeamScenario = {
    val beamConfig = BeamConfig(config)
    val vehicleType = BeamVehicleType(
      id = Id.create("car", classOf[BeamVehicleType]),
      seatingCapacity = 1,
      standingRoomCapacity = 1,
      lengthInMeter = 3,
      curbWeightInKg = 1000,
      primaryFuelType = Gasoline,
      primaryFuelConsumptionInJoulePerMeter = 0.1,
      primaryFuelCapacityInJoule = 0.1,
      vehicleCategory = Car,
      automationLevel = 1,
      maxVelocity = None,
      passengerCarUnit = 1.0,
      rechargeLevel2RateLimitInWatts = None,
      rechargeLevel3RateLimitInWatts = None,
      sampleProbabilityWithinCategory = 1.0,
      sampleProbabilityString = None,
      emissionsRatesInGramsPerMile = Some(VehicleEmissions.EmissionsProfile.init())
    )
    val tazMap = mock(classOf[TAZTreeMap])
    when(tazMap.getTAZ(any[java.lang.Double](), any[java.lang.Double]())).thenReturn(TAZ.DefaultTAZ)

    BeamScenario(
      fuelTypePrices = Map(vehicleType.primaryFuelType -> 10.0),
      vehicleTypes = Map(vehicleType.id -> vehicleType),
      privateVehicles = TrieMap.empty,
      privateVehicleInitialSoc = TrieMap.empty,
      vehicleEnergy = mock(classOf[beam.agentsim.agents.vehicles.VehicleEnergy]),
      vehicleEmissions = mock(classOf[beam.agentsim.agents.vehicles.VehicleEmissions]),
      beamConfig = beamConfig,
      dates = DateUtils(
        ZonedDateTime.parse(beamConfig.beam.routing.baseDate).toLocalDateTime,
        ZonedDateTime.parse(beamConfig.beam.routing.baseDate)
      ),
      ptFares = PtFares(List.empty),
      transportNetwork = transportNetworkForDates(config),
      networks2 = None,
      network = mock(classOf[Network]),
      trainStopQuadTree = new QuadTree[com.conveyal.gtfs.model.Stop](0.0, 0.0, 0.0, 0.0),
      tazTreeMap = tazMap,
      secondaryTazTreeMap = None,
      modeIncentives = null,
      h3taz = null,
      goodsCarriers = Map.empty,
      freightCarriers = Map.empty,
      fixedActivitiesDurations = Map.empty[String, Double]
    )
  }

  private def transportNetworkForDates(config: Config): TransportNetwork = {
    val beamConfig = BeamConfig(config)
    val network = mock(classOf[TransportNetwork])
    when(network.getTimeZone).thenReturn(ZonedDateTime.parse(beamConfig.beam.routing.baseDate).getZone)
    network
  }

  private def sampleRoutingRequest(requestId: Int, triggerId: Long): RoutingRequest =
    RoutingRequest(
      originUTM = new Location(0.0, 0.0),
      destinationUTM = new Location(1.0, 1.0),
      departureTime = 300,
      withTransit = false,
      streetVehicles = IndexedSeq.empty,
      requestId = requestId,
      triggerId = triggerId
    )

  private def freePort(): Int = {
    val socket = new ServerSocket(0)
    try socket.getLocalPort
    finally socket.close()
  }
}

private object FakeRemoteWorker {
  sealed trait Behavior
  case object ReplyWithResponse extends Behavior
  case object HoldRequest extends Behavior

  case class ReceivedRoutingRequest(requestId: Int)
}

private class FakeRemoteWorker(behavior: FakeRemoteWorker.Behavior, observer: Option[ActorRef]) extends Actor {
  import FakeRemoteWorker._

  override def receive: Receive = {
    case WorkAvailable =>
      sender() ! GimmeWork

    case request: RoutingRequest =>
      observer.foreach(_ ! ReceivedRoutingRequest(request.requestId))
      behavior match {
        case ReplyWithResponse =>
          sender() ! RoutingResponse(
            itineraries = Seq.empty[EmbodiedBeamTrip],
            requestId = request.requestId,
            request = Some(request),
            isEmbodyWithCurrentTravelTime = false,
            triggerId = request.triggerId
          )
        case HoldRequest =>
      }

    case UpdateTravelTimeRemote(_) =>
      sender() ! UpdateTravelTimeRemoteAck(self.path.toString)
  }
}
