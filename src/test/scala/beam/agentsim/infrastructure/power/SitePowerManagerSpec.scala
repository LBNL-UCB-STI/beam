package beam.agentsim.infrastructure.power

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
import beam.agentsim.infrastructure.ChargingNetwork.{ChargingSession, ChargingStation, ChargingStatus, ChargingVehicle}
import beam.agentsim.infrastructure.ChargingNetworkManager.{defaultVehicleManager, ChargingZone}
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.parking.{ParkingType, PricingModel}
import beam.agentsim.infrastructure.{ChargingNetwork, ParkingStall}
import beam.cosim.helics.BeamHelicsInterface._
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.{BeamHelper, BeamServicesImpl}
import beam.utils.TestConfigUtils.testConfig
import beam.utils.{BeamVehicleUtils, TestConfigUtils}
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.Id
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting
import org.matsim.core.utils.collections.QuadTree
import org.scalatest.{BeforeAndAfterEach, Matchers, WordSpecLike}
import org.scalatestplus.mockito.MockitoSugar

import scala.collection.concurrent.TrieMap
import scala.collection.immutable.List

class SitePowerManagerSpec
    extends TestKit(
      ActorSystem(
        "SitePowerManagerSpec",
        ConfigFactory
          .parseString("""
           |akka.log-dead-letters = 10
           |akka.actor.debug.fsm = true
           |akka.loglevel = debug
           |akka.test.timefactor = 2
           |akka.test.single-expect-default = 10 s""".stripMargin)
      )
    )
    with WordSpecLike
    with Matchers
    with MockitoSugar
    with BeamHelper
    with ImplicitSender
    with BeforeAndAfterEach {

  private val conf = system.settings.config
    .withFallback(ConfigFactory.parseString(s"""
                                               |beam.router.skim = {
                                               |  keepKLatestSkims = 1
                                               |  writeSkimsInterval = 1
                                               |  writeAggregatedSkimsInterval = 1
                                               |  taz-skimmer {
                                               |    name = "taz-skimmer"
                                               |    fileBaseName = "skimsTAZ"
                                               |  }
                                               |}
                                               |beam.agentsim.chargingNetworkManager {
                                               |  gridConnectionEnabled = false
                                               |  chargingSessionInSeconds = 300
                                               |  planningHorizonInSec = 300
                                               |  helicsFederateName = "CNMFederate"
                                               |  helicsDataOutStreamPoint = ""
                                               |  helicsDataInStreamPoint = ""
                                               |  helicsBufferSize = 1000
                                               |}
                                               |""".stripMargin))
    .withFallback(testConfig("test/input/beamville/beam.conf").resolve())
  private val beamConfig: BeamConfig = BeamConfig(conf)
  private val matsimConfig = new MatSimBeamConfigBuilder(conf).buildMatSimConf()
  matsimConfig.controler.setOutputDirectory(TestConfigUtils.testOutputDir)
  matsimConfig.controler.setOverwriteFileSetting(OverwriteFileSetting.overwriteExistingFiles)
  private val beamScenario = loadScenario(beamConfig)
  private val scenario = buildScenarioFromMatsimConfig(matsimConfig, beamScenario)
  private val injector = buildInjector(system.settings.config, beamConfig, scenario, beamScenario)
  private val beamServices = new BeamServicesImpl(injector)
  private val tazMap = beamServices.beamScenario.tazTreeMap

  val beamFederateMock: BeamFederate = mock[BeamFederate]

  private val vehicleTypes = BeamVehicleUtils.readBeamVehicleTypeFile("test/input/beamville/vehicleTypes.csv")

  val dummyChargingZone: ChargingZone = ChargingZone(
    0,
    tazMap.getTAZs.head.tazId,
    ParkingType.Workplace,
    2,
    ChargingPointType.CustomChargingPoint("ultrafast", "250.0", "DC"),
    PricingModel.FlatFee(0.0),
    defaultVehicleManager
  )

  private val vehiclesList = {
    val parkingStall1: ParkingStall = ParkingStall(
      dummyChargingZone.tazId,
      0,
      tazMap.getTAZ(dummyChargingZone.tazId).get.coord,
      0.0,
      Some(dummyChargingZone.chargingPointType),
      Some(dummyChargingZone.pricingModel),
      dummyChargingZone.parkingType
    )
    val v1 = new BeamVehicle(
      Id.createVehicleId("id1"),
      new Powertrain(0.0),
      vehicleTypes(Id.create("PHEV", classOf[BeamVehicleType]))
    )
    val v2 = new BeamVehicle(
      Id.createVehicleId("id2"),
      new Powertrain(0.0),
      vehicleTypes(Id.create("BEV", classOf[BeamVehicleType]))
    )
    v1.useParkingStall(parkingStall1)
    v2.useParkingStall(parkingStall1.copy())
    List(v1, v2)
  }

  val zoneTree = new QuadTree[ChargingZone](
    tazMap.getTAZs.head.coord.getX,
    tazMap.getTAZs.head.coord.getY,
    tazMap.getTAZs.head.coord.getX,
    tazMap.getTAZs.head.coord.getY
  )

  "SitePowerManager" should {

    val dummyStation = ChargingStation(dummyChargingZone)
    zoneTree.put(tazMap.getTAZs.head.coord.getX, tazMap.getTAZs.head.coord.getY, dummyChargingZone)
    val dummyNetwork = new ChargingNetwork(defaultVehicleManager, zoneTree)
    val trieMap =
      TrieMap[String, ChargingNetwork](defaultVehicleManager -> dummyNetwork)
    val sitePowerManager = new SitePowerManager(trieMap, beamServices)

    "get power over planning horizon 0.0 for charged vehicles" in {
      sitePowerManager.getPowerOverNextPlanningHorizon(300) shouldBe Map(
        ChargingStation(dummyChargingZone) -> 0.0
      )
    }
    "get power over planning horizon greater than 0.0 for discharged vehicles" in {
      val vehiclesMap = Map(vehiclesList.map(v => v.id -> v): _*)
      vehiclesMap.foreach(_._2.addFuel(-10000))
      sitePowerManager.getPowerOverNextPlanningHorizon(300) shouldBe Map(
        ChargingStation(dummyChargingZone) -> 0.0
      )
    }
    "replan horizon and get charging plan per vehicle" in {
      vehiclesList.foreach { v =>
        v.addFuel(v.primaryFuelLevelInJoules * 0.9 * -1)
        val (chargingVehicle, chargingStatus) = dummyNetwork.connectVehicle(0, v, v.stall.get)
        chargingStatus shouldBe ChargingStatus.Connected
        chargingVehicle shouldBe ChargingVehicle(
          v,
          defaultVehicleManager,
          v.stall.get,
          dummyStation,
          ChargingSession(),
          ChargingSession()
        )
      }
      sitePowerManager.dispatchEnergy(
        0,
        SitePowerManager.getUnlimitedPhysicalBounds(Seq(dummyStation)).value,
        Some(300)
      ) shouldBe Map(
        Id.createVehicleId("id1") -> (defaultVehicleManager, 1, 250000.0),
        Id.createVehicleId("id2") -> (defaultVehicleManager, 300, 7.5E7)
      )
    }
  }

}
