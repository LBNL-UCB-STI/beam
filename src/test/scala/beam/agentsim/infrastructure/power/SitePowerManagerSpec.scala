package beam.agentsim.infrastructure.power

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{ImplicitSender, TestKit}
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
import beam.agentsim.events.RefuelSessionEvent.NotApplicable
import beam.agentsim.infrastructure.ChargingNetwork.{ChargingStation, ChargingVehicle, ConnectionStatus}
import beam.agentsim.infrastructure.ChargingNetworkManager.ChargingPlugRequest
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.parking.{ParkingType, ParkingZone, PricingModel}
import beam.agentsim.infrastructure.taz.TAZ
import beam.agentsim.infrastructure.{ChargingNetwork, ParkingStall}
import beam.cosim.helics.BeamHelicsInterface._
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.{BeamHelper, BeamServicesImpl}
import beam.utils.TestConfigUtils.testConfig
import beam.utils.{BeamVehicleUtils, TestConfigUtils}
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting
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

  private val envelopeInUTM = {
    val envelopeInUTM = beamServices.geo.wgs2Utm(beamScenario.transportNetwork.streetLayer.envelope)
    envelopeInUTM.expandBy(beamConfig.beam.spatial.boundingBoxBuffer)
    envelopeInUTM
  }

  val beamFederateMock: BeamFederate = mock(classOf[BeamFederate])

  private val vehicleTypes = BeamVehicleUtils.readBeamVehicleTypeFile("test/input/beamville/vehicleTypes.csv")

  val taz: TAZ = tazMap.getTAZs.head

  val dummyChargingZone: ParkingZone[TAZ] = ParkingZone.init(
    None,
    taz.tazId,
    ParkingType.Workplace,
    ParkingZone.GlobalReservedFor,
    maxStalls = 2,
    chargingPointType = Some(ChargingPointType.CustomChargingPoint("ultrafast", "250.0", "DC")),
    pricingModel = Some(PricingModel.FlatFee(0.0))
  )

  private val vehiclesList = {
    val parkingStall1: ParkingStall = ParkingStall.init[TAZ](dummyChargingZone, taz.tazId, taz.coord, 0.0)
    val v1 = new BeamVehicle(
      Id.createVehicleId("id1"),
      new Powertrain(0.0),
      vehicleTypes(Id.create("PHEV", classOf[BeamVehicleType]))
    )
    val person1: Id[Person] = Id.createPersonId("dummyPerson1")
    val v2 = new BeamVehicle(
      Id.createVehicleId("id2"),
      new Powertrain(0.0),
      vehicleTypes(Id.create("BEV", classOf[BeamVehicleType]))
    )
    val person2: Id[Person] = Id.createPersonId("dummyPerson2")
    v1.useParkingStall(parkingStall1)
    v2.useParkingStall(parkingStall1.copy())
    List((v1, person1), (v2, person2))
  }

  val chargingNetwork: ChargingNetwork[TAZ] = ChargingNetwork.init(
    ParkingZone.GlobalReservedFor,
    Map(dummyChargingZone.parkingZoneId -> dummyChargingZone),
    envelopeInUTM,
    beamServices
  )

  "SitePowerManager" should {

    val dummyStation = ChargingStation(dummyChargingZone)
    val sitePowerManager = new SitePowerManager(Map(ParkingZone.GlobalReservedFor -> chargingNetwork), beamServices)

    "get power over planning horizon 0.0 for charged vehicles" in {
      sitePowerManager.requiredPowerInKWOverNextPlanningHorizon(300) shouldBe Map(
        ChargingStation(dummyChargingZone) -> 0.0
      )
    }
    "get power over planning horizon greater than 0.0 for discharged vehicles" in {
      val vehiclesMap = Map(vehiclesList.map { case (v, _) => v.id -> v }: _*)
      vehiclesMap.foreach(_._2.addFuel(-10000))
      sitePowerManager.requiredPowerInKWOverNextPlanningHorizon(300) shouldBe Map(
        ChargingStation(dummyChargingZone) -> 0.0
      )
    }
    "replan horizon and get charging plan per vehicle" in {
      vehiclesList.foreach { case (v, person) =>
        v.addFuel(v.primaryFuelLevelInJoules * 0.9 * -1)
        val request = ChargingPlugRequest(0, v, v.stall.get, person, 0)
        val Some((chargingVehicle, status)) =
          chargingNetwork.attemptToConnectVehicle(request, ActorRef.noSender)
        status shouldBe ConnectionStatus.Connected
        chargingVehicle shouldBe ChargingVehicle(
          v,
          v.stall.get,
          dummyStation,
          0,
          0,
          person,
          NotApplicable,
          None,
          ActorRef.noSender,
          ListBuffer(ConnectionStatus.Connected)
        )
        sitePowerManager.dispatchEnergy(
          300,
          chargingVehicle,
          SitePowerManager.getUnlimitedPhysicalBounds(Seq(dummyStation)).value
        ) should (be((1, 250000.0)) or be((300, 7.5e7)))
      }

    }
  }
}
