package beam.agentsim.agents.ridehail

import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import akka.util.Timeout
import beam.agentsim.agents.{Dropoff, MobilityRequestTrait, Pickup}
import beam.agentsim.agents.choice.mode.ModeIncentive
import beam.agentsim.agents.choice.mode.ModeIncentive.Incentive
import beam.agentsim.agents.ridehail.AlonsoMoraPoolingAlgForRideHail.{CustomerRequest, RVGraph, VehicleAndSchedule, _}
import beam.agentsim.agents.vehicles.{BeamVehicleType, VehiclePersonId}
import beam.agentsim.agents.vehicles.FuelType.FuelType
import beam.agentsim.infrastructure.TAZTreeMap
import beam.router.BeamSkimmer
import beam.router.Modes.BeamMode
import beam.router.osm.TollCalculator
import beam.router.r5.DefaultNetworkCoordinator
import beam.sim.BeamServices
import beam.sim.common.GeoUtilsImpl
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.utils.NetworkHelperImpl
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigFactory
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.{Coord, Id, Scenario}
import org.matsim.api.core.v01.network.Network
import org.matsim.api.core.v01.population.Person
import org.matsim.core.controler.MatsimServices
import org.matsim.core.scenario.ScenarioUtils
import org.matsim.core.utils.collections.QuadTree
import org.matsim.households.HouseholdsFactoryImpl
import org.matsim.vehicles.Vehicle
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FunSpecLike, Matchers}

import scala.collection.JavaConverters._
import scala.collection.immutable.List
import scala.concurrent.ExecutionContext

class AlonsoMoraPoolingAlgForRideHailSpec
    extends TestKit(
      ActorSystem(
        name = "AlonsoMoraPoolingAlgForRideHailSpec",
        config = ConfigFactory
          .parseString("""
               akka.log-dead-letters = 10
               akka.actor.debug.fsm = true
               akka.loglevel = debug
            """)
          .withFallback(testConfig("test/input/beamville/beam.conf").resolve())
      )
    )
    with Matchers
    with FunSpecLike
    with BeforeAndAfterAll
    with MockitoSugar
    with ImplicitSender {

  val probe: TestProbe = TestProbe.apply()
  private implicit val timeout: Timeout = Timeout(60, TimeUnit.SECONDS)
  private implicit val executionContext: ExecutionContext = system.dispatcher
  implicit val mockActorRef: ActorRef = probe.ref
  private lazy val beamConfig = BeamConfig(system.settings.config)
  private val householdsFactory: HouseholdsFactoryImpl = new HouseholdsFactoryImpl()
  private val configBuilder = new MatSimBeamConfigBuilder(system.settings.config)
  private val matsimConfig = configBuilder.buildMatSamConf()

  private lazy val beamSvc: BeamServices = {
    val scenario = ScenarioUtils.createMutableScenario(matsimConfig)
    ScenarioUtils.loadScenario(ScenarioUtils.createMutableScenario(matsimConfig))
    val tAZTreeMap: TAZTreeMap = BeamServices.getTazTreeMap("test/input/beamville/taz-centers.csv")
    val theServices = mock[BeamServices](withSettings().stubOnly())
    when(theServices.matsimServices).thenReturn(mock[MatsimServices])
    when(theServices.matsimServices.getScenario).thenReturn(mock[Scenario], scenario)
    when(theServices.matsimServices.getScenario.getNetwork).thenReturn(mock[Network])
    when(theServices.beamConfig).thenReturn(beamConfig)
    when(theServices.tazTreeMap).thenReturn(tAZTreeMap)
    when(theServices.geo).thenReturn(new GeoUtilsImpl(beamConfig))
    when(theServices.modeIncentives).thenReturn(ModeIncentive(Map[BeamMode, List[Incentive]]()))
    when(theServices.fuelTypePrices).thenReturn(mock[Map[FuelType, Double]])
    when(theServices.vehicleTypes).thenReturn(Map[Id[BeamVehicleType], BeamVehicleType]())
    theServices
  }

  describe("AlonsoMoraPoolingAlgForRideHail") {
    it("Creates a consistent plan") {
      implicit val skimmer: BeamSkimmer = new BeamSkimmer()
      val sc = AlonsoMoraPoolingAlgForRideHailSpec.scenario1
      val alg: AlonsoMoraPoolingAlgForRideHail =
        new AlonsoMoraPoolingAlgForRideHail(
          AlonsoMoraPoolingAlgForRideHailSpec.demandSpatialIndex(sc._2),
          sc._1,
          Map[MobilityRequestTrait, Int]((Pickup, 6 * 60), (Dropoff, 10 * 60)),
          maxRequestsPerVehicle = 1000,
          beamSvc
        )

      val rvGraph: RVGraph = alg.pairwiseRVGraph
      for (e <- rvGraph.edgeSet.asScala) {
        rvGraph.getEdgeSource(e).getId match {
          case "p1" =>
            assert(
              rvGraph.getEdgeTarget(e).getId.equals("p2") ||
              rvGraph.getEdgeTarget(e).getId.equals("p4")
            )
          case "p2" =>
            assert(
              rvGraph.getEdgeTarget(e).getId.equals("p1") ||
              rvGraph.getEdgeTarget(e).getId.equals("p3") ||
              rvGraph.getEdgeTarget(e).getId.equals("p4")
            )
          case "p3" =>
            assert(
              rvGraph.getEdgeTarget(e).getId.equals("p2")
            )
          case "p4" =>
            assert(
              rvGraph.getEdgeTarget(e).getId.equals("p1") ||
              rvGraph.getEdgeTarget(e).getId.equals("p2")
            )
          case "v1" =>
            assert(
              rvGraph.getEdgeTarget(e).getId.equals("p2") ||
              rvGraph.getEdgeTarget(e).getId.equals("p3")
            )
          case "v2" =>
            assert(
              rvGraph.getEdgeTarget(e).getId.equals("p1") ||
              rvGraph.getEdgeTarget(e).getId.equals("p2") ||
              rvGraph.getEdgeTarget(e).getId.equals("p3") ||
              rvGraph.getEdgeTarget(e).getId.equals("p4")
            )
        }
      }

      val rtvGraph = alg.rTVGraph(rvGraph, beamSvc)

      for (v <- rtvGraph.vertexSet().asScala.filter(_.isInstanceOf[RideHailTrip])) {
        v.getId match {
          case "trip:[p3] -> " =>
            assert(
              rtvGraph.outgoingEdgesOf(v).asScala.map(e => rtvGraph.getEdgeTarget(e).getId).contains("v1") ||
              rtvGraph.outgoingEdgesOf(v).asScala.map(e => rtvGraph.getEdgeTarget(e).getId).contains("v2") ||
              rtvGraph.outgoingEdgesOf(v).asScala.map(e => rtvGraph.getEdgeTarget(e).getId).contains("p3")
            )
          case "trip:[p1] -> " =>
            assert(
              rtvGraph.outgoingEdgesOf(v).asScala.map(e => rtvGraph.getEdgeTarget(e).getId).contains("p1") ||
              rtvGraph.outgoingEdgesOf(v).asScala.map(e => rtvGraph.getEdgeTarget(e).getId).contains("v2")
            )
          case "trip:[p2] -> " =>
            assert(
              rtvGraph.outgoingEdgesOf(v).asScala.map(e => rtvGraph.getEdgeTarget(e).getId).contains("p2") ||
              rtvGraph.outgoingEdgesOf(v).asScala.map(e => rtvGraph.getEdgeTarget(e).getId).contains("v2")
            )
          case "trip:[p4] -> " =>
            assert(
              rtvGraph.outgoingEdgesOf(v).asScala.map(e => rtvGraph.getEdgeTarget(e).getId).contains("p4") ||
              rtvGraph.outgoingEdgesOf(v).asScala.map(e => rtvGraph.getEdgeTarget(e).getId).contains("v2")
            )
          case "trip:[p1] -> [p4] -> " =>
            assert(
              rtvGraph.outgoingEdgesOf(v).asScala.map(e => rtvGraph.getEdgeTarget(e).getId).contains("v2") ||
              rtvGraph.outgoingEdgesOf(v).asScala.map(e => rtvGraph.getEdgeTarget(e).getId).contains("p1") ||
              rtvGraph.outgoingEdgesOf(v).asScala.map(e => rtvGraph.getEdgeTarget(e).getId).contains("p4")
            )
          case "trip:[p2] -> [p3] -> " =>
            assert(
              rtvGraph.outgoingEdgesOf(v).asScala.map(e => rtvGraph.getEdgeTarget(e).getId).contains("v2") ||
              rtvGraph.outgoingEdgesOf(v).asScala.map(e => rtvGraph.getEdgeTarget(e).getId).contains("p2") ||
              rtvGraph.outgoingEdgesOf(v).asScala.map(e => rtvGraph.getEdgeTarget(e).getId).contains("p3")
            )
          case "trip:[p2] -> [p4] -> " =>
            assert(
              rtvGraph.outgoingEdgesOf(v).asScala.map(e => rtvGraph.getEdgeTarget(e).getId).contains("v2") ||
              rtvGraph.outgoingEdgesOf(v).asScala.map(e => rtvGraph.getEdgeTarget(e).getId).contains("p2") ||
              rtvGraph.outgoingEdgesOf(v).asScala.map(e => rtvGraph.getEdgeTarget(e).getId).contains("p4")
            )
          case _ =>
        }
      }

      val assignment = alg.greedyAssignment(rtvGraph)

      for (row <- assignment) {
        assert(row._1.getId == "trip:[p1] -> [p4] -> " || row._1.getId == "trip:[p3] -> ")
        assert(row._2.getId == "v2" || row._2.getId == "v1")
      }
    }
  }

}

object AlonsoMoraPoolingAlgForRideHailSpec {

  def scenario1(
    implicit skimmer: BeamSkimmer,
    mockActorRef: ActorRef
  ): (List[VehicleAndSchedule], List[CustomerRequest]) = {
    import scala.concurrent.duration._
    val v1: VehicleAndSchedule = createVehicleAndSchedule("v1", new Coord(5000, 5000), 8.hours.toSeconds.toInt)
    val v2: VehicleAndSchedule = createVehicleAndSchedule("v2", new Coord(2000, 2000), 8.hours.toSeconds.toInt)
    val p1Req: CustomerRequest =
      createPersonRequest(
        makeVehPersonId("p1"),
        new Coord(1000, 2000),
        8.hours.toSeconds.toInt,
        new Coord(18000, 19000)
      )
    val p4Req: CustomerRequest =
      createPersonRequest(
        makeVehPersonId("p4"),
        new Coord(2000, 1000),
        (8.hours.toSeconds + 5.minutes.toSeconds).toInt,
        new Coord(20000, 18000)
      )
    val p2Req: CustomerRequest =
      createPersonRequest(
        makeVehPersonId("p2"),
        new Coord(3000, 3000),
        (8.hours.toSeconds + 1.minutes.toSeconds).toInt,
        new Coord(19000, 18000)
      )
    val p3Req: CustomerRequest =
      createPersonRequest(
        makeVehPersonId("p3"),
        new Coord(4000, 4000),
        (8.hours.toSeconds + 2.minutes.toSeconds).toInt,
        new Coord(21000, 20000)
      )
    (List(v1, v2), List(p1Req, p2Req, p3Req, p4Req))
  }

  def makeVehPersonId(perId: Id[Person])(implicit mockActorRef: ActorRef): VehiclePersonId =
    VehiclePersonId(Id.create(perId, classOf[Vehicle]), perId, mockActorRef)

  def makeVehPersonId(perId: String)(implicit mockActorRef: ActorRef): VehiclePersonId =
    makeVehPersonId(Id.create(perId, classOf[Person]))

  def demandSpatialIndex(demand: List[CustomerRequest]): QuadTree[CustomerRequest] = {
    val boundingBox: Envelope = getEnvelopeFromDemand(demand)
    val spatialDemand = new QuadTree[CustomerRequest](
      boundingBox.getMinX,
      boundingBox.getMinY,
      boundingBox.getMaxX,
      boundingBox.getMaxY
    )
    demand.foreach { d =>
      spatialDemand.put(d.pickup.activity.getCoord.getX, d.pickup.activity.getCoord.getY, d)
    }
    spatialDemand
  }

  def getEnvelopeFromDemand(demand: List[CustomerRequest]): Envelope = {
    val minx = demand.map(_.pickup.activity.getCoord.getX).min
    val maxx = demand.map(_.pickup.activity.getCoord.getX).max
    val miny = demand.map(_.pickup.activity.getCoord.getY).min
    val maxy = demand.map(_.pickup.activity.getCoord.getY).max
    new Envelope(minx, maxx, miny, maxy)
  }

}
