package beam.agentsim.infrastructure.power

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
import beam.agentsim.infrastructure.ChargingNetworkManager.ChargingZone
import beam.agentsim.infrastructure.ParkingStall
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.parking.{ParkingType, PricingModel}
import beam.agentsim.infrastructure.taz.TAZ
import beam.cosim.helics.BeamFederate
import beam.sim.{BeamHelper, BeamServicesImpl}
import beam.sim.{BeamServices, BeamServicesImpl}
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.utils.TestConfigUtils.testConfig
import beam.utils.{BeamVehicleUtils, TestConfigUtils}
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.Id
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting
import org.mockito.Mockito.when
import org.scalatest.{BeforeAndAfterEach, Matchers, WordSpecLike}
import org.scalatestplus.mockito.MockitoSugar

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

  private val filesPath = s"${System.getenv("PWD")}/test/test-resources/beam/input"
  private val conf = system.settings.config
    .withFallback(ConfigFactory.parseString(s"""
                                               |beam.agentsim.agents.vehicles.vehicleTypesFilePath = $filesPath"/vehicleTypes-simple.csv"
                                               |beam.agentsim.agents.vehicles.vehiclesFilePath = $filesPath"/vehicles-simple.csv"
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
                                               |  helicsFederateName = "BeamCNM"
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
  val beamServices = new BeamServicesImpl(injector)

  val beamFederateMock = mock[BeamFederate]
  val parkingStall1 = mock[ParkingStall]
  when(parkingStall1.chargingPointType).thenReturn(Some(ChargingPointType.ChargingStationType1))
  when(parkingStall1.locationUTM).thenReturn(beamServices.beamScenario.tazTreeMap.getTAZs.head.coord)
  when(parkingStall1.parkingZoneId).thenReturn(1)

  val parkingStall2 = mock[ParkingStall]
  when(parkingStall2.chargingPointType).thenReturn(Some(ChargingPointType.ChargingStationType1))
  when(parkingStall2.locationUTM).thenReturn(beamServices.beamScenario.tazTreeMap.getTAZs.head.coord)
  when(parkingStall2.parkingZoneId).thenReturn(1)

  private val vehicleTypes = BeamVehicleUtils.readBeamVehicleTypeFile("test/input/beamville/vehicleTypes.csv")

  val dummyChargingZone = ChargingZone(
    1,
    Id.create("Dummy", classOf[TAZ]),
    ParkingType.Public,
    1,
    1,
    ChargingPointType.ChargingStationType1,
    PricingModel.FlatFee(0.0)
  )

  private def vehiclesList = {
    val v1 = new BeamVehicle(
      Id.createVehicleId("id1"),
      new Powertrain(0.0),
      vehicleTypes(Id.create("PHEV", classOf[BeamVehicleType]))
    )
    v1.useParkingStall(parkingStall1)
    val v2 = new BeamVehicle(
      Id.createVehicleId("id2"),
      new Powertrain(0.0),
      vehicleTypes(Id.create("CAV", classOf[BeamVehicleType]))
    )
    v2.useParkingStall(parkingStall2)
    List(v1, v2)
  }

  "SitePowerManager" should {
    val sitePowerManager = new SitePowerManager(Map[Int, ChargingZone](1 -> dummyChargingZone), beamServices)

    "get power over planning horizon 0.0 for charged vehicles" in {
      sitePowerManager.getPowerOverNextPlanningHorizon(300) shouldBe Map(
        dummyChargingZone -> ChargingPointType.getChargingPointInstalledPowerInKw(dummyChargingZone.chargingPointType)
      )
    }
    "get power over planning horizon greater than 0.0 for discharged vehicles" in {
      val vehiclesMap = Map(vehiclesList.map(v => v.id -> v): _*)
      vehiclesMap.foreach(_._2.addFuel(-10000))
      sitePowerManager.getPowerOverNextPlanningHorizon(300) shouldBe Map(
        dummyChargingZone -> ChargingPointType.getChargingPointInstalledPowerInKw(dummyChargingZone.chargingPointType)
      )
    }
    "replan horizon and get charging plan per vehicle" in {
      val vehiclesMap = Map(vehiclesList.map(v => v.id -> v): _*)
      vehiclesMap.foreach(_._2.addFuel(-10000))
      sitePowerManager.replanHorizonAndGetChargingPlanPerVehicle(vehiclesMap.values, 300) shouldBe Map(
        Id.createVehicleId("id1") -> (1, 7200.0, 7200.0),
        Id.createVehicleId("id2") -> (1, 7200.0, 7200.0)
      )
    }
  }

}
