package beam.agentsim.infrastructure.power

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{ImplicitSender, TestKit}
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType, VehicleManager}
import beam.agentsim.infrastructure.ChargingNetwork.{ChargingStation, ChargingVehicle, ConnectionStatus}
import beam.agentsim.infrastructure.ChargingNetworkManager.ChargingZone
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
import org.mockito.Mockito.mock
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.collection.immutable.List
import scala.collection.mutable.ListBuffer

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
    with AnyWordSpecLike
    with Matchers
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
       |  timeStepInSeconds = 300
       |
       |  helics {
       |    connectionEnabled = false
       |    coreInitString = "--federates=1 --broker_address=tcp://127.0.0.1"
       |    coreType = "zmq"
       |    timeDeltaProperty = 1.0
       |    intLogLevel = 1
       |    federateName = "CNMFederate"
       |    dataOutStreamPoint = ""
       |    dataInStreamPoint = ""
       |    bufferSize = 100
       |  }
       |
       |  chargingPoint {
       |    thresholdXFCinKW = 250
       |    thresholdDCFCinKW = 50
       |  }
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

  val beamFederateMock: BeamFederate = mock(classOf[BeamFederate])

  private val vehicleTypes = BeamVehicleUtils.readBeamVehicleTypeFile("test/input/beamville/vehicleTypes.csv")

  val dummyChargingZone: ChargingZone = ChargingZone(
    tazMap.getTAZs.head.tazId,
    tazMap.getTAZs.head.tazId,
    ParkingType.Workplace,
    2,
    ChargingPointType.CustomChargingPoint("ultrafast", "250.0", "DC"),
    PricingModel.FlatFee(0.0),
    None
  )

  private val vehiclesList = {
    val parkingStall1: ParkingStall = ParkingStall(
      dummyChargingZone.geoId,
      dummyChargingZone.tazId,
      0,
      tazMap.getTAZ(dummyChargingZone.tazId).get.coord,
      0.0,
      Some(dummyChargingZone.chargingPointType),
      Some(dummyChargingZone.pricingModel),
      dummyChargingZone.parkingType,
      reservedFor = Seq.empty
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
    val dummyNetwork = new ChargingNetwork(None, zoneTree)
    val trieMap = Map[Option[Id[VehicleManager]], ChargingNetwork](None -> dummyNetwork)
    val sitePowerManager = new SitePowerManager(trieMap, beamServices)

    "get power over planning horizon 0.0 for charged vehicles" in {
      sitePowerManager.requiredPowerInKWOverNextPlanningHorizon(300) shouldBe Map(
        ChargingStation(dummyChargingZone) -> 0.0
      )
    }
    "get power over planning horizon greater than 0.0 for discharged vehicles" in {
      val vehiclesMap = Map(vehiclesList.map(v => v.id -> v): _*)
      vehiclesMap.foreach(_._2.addFuel(-10000))
      sitePowerManager.requiredPowerInKWOverNextPlanningHorizon(300) shouldBe Map(
        ChargingStation(dummyChargingZone) -> 0.0
      )
    }
    "replan horizon and get charging plan per vehicle" in {
      vehiclesList.foreach { v =>
        v.addFuel(v.primaryFuelLevelInJoules * 0.9 * -1)
        val Some(chargingVehicle) = dummyNetwork.attemptToConnectVehicle(0, v, ActorRef.noSender)
        chargingVehicle shouldBe ChargingVehicle(
          v,
          v.stall.get,
          dummyStation,
          0,
          0,
          ActorRef.noSender,
          ListBuffer(ConnectionStatus.Connected)
        )
        sitePowerManager.dispatchEnergy(
          300,
          chargingVehicle,
          SitePowerManager.getUnlimitedPhysicalBounds(Seq(dummyStation)).value
        ) should (be((1, 250000.0)) or be((300, 7.5E7)))
      }

    }
  }

}
