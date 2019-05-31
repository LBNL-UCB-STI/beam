package beam.agentsim.agents.ridehail

import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import akka.util.Timeout
import beam.agentsim.agents.{Dropoff, MobilityRequestType, Pickup}
import beam.agentsim.agents.choice.mode.ModeIncentive
import beam.agentsim.agents.choice.mode.ModeIncentive.Incentive
import beam.agentsim.agents.planning.BeamPlan
import beam.agentsim.agents.ridehail.AlonsoMoraPoolingAlgForRideHail.{CustomerRequest, RVGraph, VehicleAndSchedule, _}
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.FuelType.FuelType
import beam.agentsim.infrastructure.TAZTreeMap
import beam.router.BeamSkimmer
import beam.router.Modes.BeamMode
import beam.sim.BeamServices
import beam.sim.common.GeoUtilsImpl
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.{Coord, Id, Scenario}
import org.matsim.api.core.v01.network.Network
import org.matsim.core.controler.MatsimServices
import org.matsim.core.scenario.ScenarioUtils
import org.matsim.households.HouseholdsFactoryImpl
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FunSpecLike, Matchers}

import scala.collection.JavaConverters._
import scala.collection.immutable.List
import scala.collection.mutable
import scala.concurrent.{Await, ExecutionContext}

class AsyncAlonsoMoraAlgForRideHailSpec
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
  private val matsimConfig = configBuilder.buildMatSimConf()

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

  describe("AsyncAlonsoMoraAlgForRideHailSpec") {
    it("Creates a consistent plan") {
      implicit val skimmer: BeamSkimmer = new BeamSkimmer(beamConfig, beamSvc)
      val sc = AlonsoMoraPoolingAlgForRideHailSpec.scenario1
      val alg: AsyncAlonsoMoraAlgForRideHail =
        new AsyncAlonsoMoraAlgForRideHail(
          AlonsoMoraPoolingAlgForRideHailSpec.demandSpatialIndex(sc._2),
          sc._1,
          Map[MobilityRequestType, Int]((Pickup, 7 * 60), (Dropoff, 10 * 60)),
          maxRequestsPerVehicle = 1000,
          beamSvc
        )

      import scala.concurrent.duration._
      val assignment = Await.result(alg.greedyAssignment(), atMost = 10.minutes).toArray
      assert(assignment(0)._2.getId == "v2")
      assignment(0)._1.requests.foreach(p => assert(p.getId == "p1" || p.getId == "p4"))
      assert(assignment(1)._2.getId == "v1")
      assert(assignment(1)._1.requests.head.getId == "p3")
    }

    it("Creates a consistent plan considering a geofence ") {
      implicit val skimmer: BeamSkimmer = new BeamSkimmer(beamConfig, beamSvc)
      val sc = AlonsoMoraPoolingAlgForRideHailSpec.scenarioGeoFence
      val alg: AsyncAlonsoMoraAlgForRideHail =
        new AsyncAlonsoMoraAlgForRideHail(
          AlonsoMoraPoolingAlgForRideHailSpec.demandSpatialIndex(sc._2),
          sc._1,
          Map[MobilityRequestType, Int]((Pickup, 7 * 60), (Dropoff, 10 * 60)),
          maxRequestsPerVehicle = 1000,
          beamSvc
        )
      import scala.concurrent.duration._
      val assignment = Await.result(alg.greedyAssignment(), atMost = 10.minutes).toArray
      assert(assignment(0)._2.getId == "v2")
      assignment(0)._1.requests.foreach(p => assert(p.getId == "p1" || p.getId == "p4"))
      assert(assignment(1)._2.getId == "v1")
      assert(assignment(1)._1.requests.head.getId == "p2")
    }

    ignore("scales") {
      import org.matsim.core.config.ConfigUtils
      import org.matsim.core.population.io.PopulationReader
      import org.matsim.core.scenario.ScenarioUtils
      val sc = ScenarioUtils.createScenario(ConfigUtils.createConfig())
      new PopulationReader(sc).readFile("test/input/sf-light/sample/25k/population.xml.gz")
      implicit val skimmer: BeamSkimmer = new BeamSkimmer(beamConfig, beamSvc)

      val requests = mutable.ListBuffer.empty[CustomerRequest]
      sc.getPopulation.getPersons.values.asScala.map(p => BeamPlan(p.getSelectedPlan)).foreach { plan =>
        plan.trips.sliding(2).foreach {
          case Seq(prevTrip, curTrip) =>
            requests append createPersonRequest(
              AlonsoMoraPoolingAlgForRideHailSpec.makeVehPersonId(plan.getPerson.getId.toString),
              prevTrip.activity.getCoord,
              prevTrip.activity.getEndTime.toInt,
              curTrip.activity.getCoord
            )
        }
      }

      val minx = requests.map(_.pickup.activity.getCoord.getX).min
      val maxx = requests.map(_.pickup.activity.getCoord.getX).max
      val miny = requests.map(_.pickup.activity.getCoord.getY).min
      val maxy = requests.map(_.pickup.activity.getCoord.getY).max
      val rnd = new scala.util.Random
      val timeWindow = 300
      val fleetSize = 200
      val operation = "SYNC"

      val t0 = System.nanoTime()
      (28800 to 32400 by timeWindow).foreach { i =>
        println("")
        println(i / 3600.0)
        val demand = requests.filter(x => x.pickup.baselineNonPooledTime >= i && x.pickup.baselineNonPooledTime < i + timeWindow)
        val fleet = mutable.ListBuffer.empty[VehicleAndSchedule]
        (0 to fleetSize).foreach { j =>
          print(s"$j,")
          fleet.append(
            createVehicleAndSchedule(
              "v" + j,
              new Coord(minx + rnd.nextDouble() * (maxx - minx), miny + rnd.nextDouble() * (maxy - miny)),
              i,
              None,
              4
            )
          )
        }

        var assignment = List.empty[(RideHailTrip, VehicleAndSchedule, Int)]
        if (demand.nonEmpty) {
          operation match {
            case "ASYNC" =>
              val alg: AsyncAlonsoMoraAlgForRideHail =
                new AsyncAlonsoMoraAlgForRideHail(
                  AlonsoMoraPoolingAlgForRideHailSpec.demandSpatialIndex(demand.toList),
                  fleet.toList,
                  Map[MobilityRequestType, Int]((Pickup, 6 * 60), (Dropoff, 10 * 60)),
                  maxRequestsPerVehicle = 100,
                  beamSvc
                )
              import scala.concurrent.duration._
              assignment = Await.result(alg.greedyAssignment(), atMost = 10.minutes)
            case "SYNC" =>
              val alg: AlonsoMoraPoolingAlgForRideHail =
                new AlonsoMoraPoolingAlgForRideHail(
                  AlonsoMoraPoolingAlgForRideHailSpec.demandSpatialIndex(demand.toList),
                  fleet.toList,
                  Map[MobilityRequestType, Int]((Pickup, 6 * 60), (Dropoff, 10 * 60)),
                  maxRequestsPerVehicle = 100,
                  beamSvc
                )
              val rvGraph: RVGraph = alg.pairwiseRVGraph
              val rtvGraph = alg.rTVGraph(rvGraph, beamSvc)
              assignment = alg.greedyAssignment(rtvGraph)
            case _ =>
          }
        }
      }
      val t1 = System.nanoTime()
      println("Elapsed time: " + (t1 - t0) / 1E9 + "s")
    }
  }

}
