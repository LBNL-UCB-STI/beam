package beam.agentsim.infrastructure

import akka.actor.{ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import beam.agentsim.Resource.ReleaseParkingStall
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.InitializeTrigger
import beam.agentsim.agents.vehicles.{BeamVehicle, VehicleManager}
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.parking.{ParkingType, PricingModel}
import beam.agentsim.scheduler.BeamAgentScheduler
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.{BeamHelper, BeamServicesImpl}
import beam.utils.TestConfigUtils.testConfig
import beam.utils.{DateUtils, StuckFinder, TestConfigUtils}
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.Id
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.language.postfixOps

class ChargingNetworkManagerSpec
    extends TestKit(
      ActorSystem(
        "ChargingNetworkManagerSpec",
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

  private val filesPath = s"${System.getenv("PWD")}/test/test-resources/beam/input"
  private val conf = system.settings.config
    .withFallback(ConfigFactory.parseString(s"""
     |beam.agentsim.agents.vehicles.vehicleTypesFilePath = $filesPath"/vehicleTypes-simple.csv"
     |beam.agentsim.agents.vehicles.vehiclesFilePath = $filesPath"/vehicles-simple.csv"
     |beam.agentsim.taz.parkingFilePath = "test/input/beamville/parking/taz-parking-limited.csv"
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

  import ChargingNetworkManager._

  private val beamConfig: BeamConfig = BeamConfig(conf)
  private val matsimConfig = new MatSimBeamConfigBuilder(conf).buildMatSimConf()
  matsimConfig.controler.setOutputDirectory(TestConfigUtils.testOutputDir)
  matsimConfig.controler.setOverwriteFileSetting(OverwriteFileSetting.overwriteExistingFiles)
  private val beamScenario = loadScenario(beamConfig)
  private val scenario = buildScenarioFromMatsimConfig(matsimConfig, beamScenario)
  private val injector = buildInjector(system.settings.config, beamConfig, scenario, beamScenario)
  private val beamServices = new BeamServicesImpl(injector)

  val timeStepInSeconds: Int = beamConfig.beam.agentsim.chargingNetworkManager.timeStepInSeconds

  def getBeamVilleCar(
    vehicleId: String,
    parkingStall: ParkingStall,
    fuelToSubtractInPercent: Double = 0.0
  ): BeamVehicle = {
    val beamVilleCar = beamScenario.privateVehicles(Id.create(vehicleId, classOf[BeamVehicle]))
    val fuelToSubtract = beamVilleCar.primaryFuelLevelInJoules * fuelToSubtractInPercent
    beamVilleCar.addFuel(-1 * fuelToSubtract)
    beamVilleCar.connectToChargingPoint(0)
    beamVilleCar.useParkingStall(parkingStall)
    beamVilleCar
  }

  class BeamAgentSchedulerRedirect(
    override val beamConfig: BeamConfig,
    stopTick: Int,
    override val maxWindow: Int,
    override val stuckFinder: StuckFinder
  ) extends BeamAgentScheduler(beamConfig, stopTick, maxWindow, stuckFinder) {
    override def receive: Receive = {
      case Finish => context.stop(self)
      case msg    => testActor ! msg
    }
  }

  private val taz2 = beamServices.beamScenario.tazTreeMap.getTAZ("2").get
  private val chargingType = ChargingPointType.CustomChargingPoint("ultrafast", "250.0", "DC")
  private val pricingModel = PricingModel.Block(0.0, 0)

  val parkingStall: ParkingStall =
    ParkingStall(
      taz2.tazId,
      taz2.tazId,
      0,
      taz2.coord,
      0.0,
      Some(chargingType),
      Some(pricingModel),
      ParkingType.Public,
      reservedFor = Seq.empty
    )
  var scheduler: TestActorRef[BeamAgentSchedulerRedirect] = _
  var parkingManager: TestProbe = _
  var personAgent: TestProbe = _
  var chargingNetworkManager: TestActorRef[ChargingNetworkManager] = _

  private val envelopeInUTM = {
    val envelopeInUTM = beamServices.geo.wgs2Utm(beamScenario.transportNetwork.streetLayer.envelope)
    envelopeInUTM.expandBy(beamConfig.beam.spatial.boundingBoxBuffer)
    envelopeInUTM
  }

  "ChargingNetworkManager" should {
    "process trigger PlanningTimeOutTrigger" in {
      chargingNetworkManager ! TriggerWithId(PlanEnergyDispatchTrigger(0), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(PlanEnergyDispatchTrigger(300), chargingNetworkManager)
      )
      expectNoMessage()
    }

    "process the last trigger PlanningTimeOutTrigger" in {
      chargingNetworkManager ! TriggerWithId(PlanEnergyDispatchTrigger(DateUtils.getEndOfTime(beamConfig)), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector()
      expectNoMessage()
    }

    "add a vehicle to charging queue with full fuel level but ends up with no fuel added" in {
      val beamVilleCar = getBeamVilleCar("2", parkingStall)
      beamVilleCar.primaryFuelLevelInJoules should be(2.7E8)

      chargingNetworkManager ! TriggerWithId(PlanEnergyDispatchTrigger(0), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(PlanEnergyDispatchTrigger(300), chargingNetworkManager)
      )
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(2.7E8)

      chargingNetworkManager ! ChargingPlugRequest(10, beamVilleCar, 0)
      expectMsgType[StartingRefuelSession]
      expectMsgType[ScheduleTrigger] should be(
        ScheduleTrigger(ChargingTimeOutTrigger(10, beamVilleCar), chargingNetworkManager)
      )
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(2.7E8)

      chargingNetworkManager ! TriggerWithId(PlanEnergyDispatchTrigger(300), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(PlanEnergyDispatchTrigger(600), chargingNetworkManager)
      )
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(2.7E8)
      beamVilleCar.isConnectedToChargingPoint() should be(false)
    }

    "add a vehicle to charging queue with some fuel required and will charge" in {
      val beamVilleCar = getBeamVilleCar("2", parkingStall, 0.6)

      chargingNetworkManager ! TriggerWithId(PlanEnergyDispatchTrigger(0), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(PlanEnergyDispatchTrigger(300), chargingNetworkManager)
      )
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(1.08E8)

      chargingNetworkManager ! ChargingPlugRequest(10, beamVilleCar, 0)
      expectMsgType[StartingRefuelSession]
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(1.08E8)

      chargingNetworkManager ! TriggerWithId(PlanEnergyDispatchTrigger(300), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(ChargingTimeOutTrigger(442, beamVilleCar), chargingNetworkManager),
        ScheduleTrigger(PlanEnergyDispatchTrigger(600), chargingNetworkManager)
      )
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(1.805E8)

      chargingNetworkManager ! TriggerWithId(ChargingTimeOutTrigger(442, beamVilleCar), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector()
      expectNoMessage()
      parkingManager.expectMsgType[ReleaseParkingStall]
      beamVilleCar.primaryFuelLevelInJoules should be(2.16E8)
    }

    "add a vehicle to charging queue with a lot fuel required and will charge in 2 cycles" in {
      val beamVilleCar = getBeamVilleCar("2", parkingStall, 0.5)

      chargingNetworkManager ! TriggerWithId(PlanEnergyDispatchTrigger(0), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(PlanEnergyDispatchTrigger(300), chargingNetworkManager)
      )
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(1.35E8)

      chargingNetworkManager ! ChargingPlugRequest(10, beamVilleCar, 0)
      expectMsgType[StartingRefuelSession]
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(1.35E8)

      chargingNetworkManager ! TriggerWithId(PlanEnergyDispatchTrigger(300), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(ChargingTimeOutTrigger(334, beamVilleCar), chargingNetworkManager),
        ScheduleTrigger(PlanEnergyDispatchTrigger(600), chargingNetworkManager)
      )
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(2.075E8)

      chargingNetworkManager ! TriggerWithId(ChargingTimeOutTrigger(334, beamVilleCar), 0)
      expectMsgType[CompletionNotice] shouldBe CompletionNotice(0)
      expectNoMessage()
      parkingManager.expectMsgType[ReleaseParkingStall]
      beamVilleCar.primaryFuelLevelInJoules should be(2.16E8)
    }

    "add a vehicle to charging queue with a lot fuel required but unplug event happens before 1st cycle" in {
      val beamVilleCar = getBeamVilleCar("2", parkingStall, 0.5)

      chargingNetworkManager ! TriggerWithId(PlanEnergyDispatchTrigger(0), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(PlanEnergyDispatchTrigger(300), chargingNetworkManager)
      )
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(1.35E8)

      chargingNetworkManager ! ChargingPlugRequest(10, beamVilleCar, 0)
      expectMsgType[StartingRefuelSession]
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(1.35E8)

      chargingNetworkManager ! ChargingUnplugRequest(35, beamVilleCar, 0)
      expectMsgType[EndingRefuelSession]
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(1.4125E8)

      chargingNetworkManager ! TriggerWithId(ChargingTimeOutTrigger(35, beamVilleCar), 0)
      expectMsgType[CompletionNotice]
      beamVilleCar.primaryFuelLevelInJoules should be(1.4125E8)

      chargingNetworkManager ! TriggerWithId(PlanEnergyDispatchTrigger(300), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(PlanEnergyDispatchTrigger(600), chargingNetworkManager)
      )
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(1.4125E8)
    }

    "add a vehicle to charging queue with a lot fuel required but unplug event happens after 1st cycle" in {
      val beamVilleCar = getBeamVilleCar("2", parkingStall, 0.8)

      chargingNetworkManager ! TriggerWithId(PlanEnergyDispatchTrigger(0), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(PlanEnergyDispatchTrigger(300), chargingNetworkManager)
      )
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(5.4E7)

      chargingNetworkManager ! ChargingPlugRequest(10, beamVilleCar, 0)
      expectMsgType[StartingRefuelSession]
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(5.4E7)

      chargingNetworkManager ! TriggerWithId(PlanEnergyDispatchTrigger(300), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(PlanEnergyDispatchTrigger(600), chargingNetworkManager)
      )
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(1.265E8)

      chargingNetworkManager ! ChargingUnplugRequest(315, beamVilleCar, 0)
      expectMsgType[EndingRefuelSession]
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(1.3025E8)

      chargingNetworkManager ! TriggerWithId(PlanEnergyDispatchTrigger(600), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(PlanEnergyDispatchTrigger(900), chargingNetworkManager)
      )
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(1.3025E8)
    }

    "add a vehicle to charging queue with a lot fuel required but unplug event happens after 2nd cycle" in {
      chargingNetworkManager ! TriggerWithId(PlanEnergyDispatchTrigger(0), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(PlanEnergyDispatchTrigger(300), chargingNetworkManager)
      )

      val beamVilleCar = getBeamVilleCar("2", parkingStall, 0.8)
      chargingNetworkManager ! ChargingPlugRequest(100, beamVilleCar, 0)
      expectMsgType[StartingRefuelSession]
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(5.4E7)

      chargingNetworkManager ! TriggerWithId(PlanEnergyDispatchTrigger(300), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(PlanEnergyDispatchTrigger(600), chargingNetworkManager)
      )
      beamVilleCar.primaryFuelLevelInJoules should be(1.04E8)

      chargingNetworkManager ! TriggerWithId(PlanEnergyDispatchTrigger(600), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(ChargingTimeOutTrigger(748, beamVilleCar), chargingNetworkManager),
        ScheduleTrigger(PlanEnergyDispatchTrigger(900), chargingNetworkManager)
      )
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(1.79E8)

      chargingNetworkManager ! TriggerWithId(ChargingTimeOutTrigger(748, beamVilleCar), 0)
      expectMsgType[CompletionNotice]
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(2.16E8)

      chargingNetworkManager ! ChargingUnplugRequest(750, beamVilleCar, 0)
      expectMsgType[UnhandledVehicle]
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(2.16E8)
    }

    "add two vehicles to charging queue and ends both charged" in {
      val beamVilleCar2 = getBeamVilleCar("2", parkingStall, 0.6)
      val beamVilleCar3 = getBeamVilleCar("3", parkingStall, 0.6)

      chargingNetworkManager ! TriggerWithId(PlanEnergyDispatchTrigger(0), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(PlanEnergyDispatchTrigger(300), chargingNetworkManager)
      )
      expectNoMessage()
      beamVilleCar2.primaryFuelLevelInJoules should be(1.08E8)
      beamVilleCar3.primaryFuelLevelInJoules should be(1.08E8)

      chargingNetworkManager ! ChargingPlugRequest(10, beamVilleCar2, 0)
      expectMsgType[StartingRefuelSession]
      expectNoMessage()
      beamVilleCar2.primaryFuelLevelInJoules should be(1.08E8)

      chargingNetworkManager ! ChargingPlugRequest(10, beamVilleCar3, 112)
      expectMsgType[WaitingInLine] should be(WaitingInLine(10, beamVilleCar3.id, 112))
      expectNoMessage()
      beamVilleCar3.primaryFuelLevelInJoules should be(1.08E8)

      chargingNetworkManager ! TriggerWithId(PlanEnergyDispatchTrigger(300), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(ChargingTimeOutTrigger(442, beamVilleCar2), chargingNetworkManager),
        ScheduleTrigger(PlanEnergyDispatchTrigger(600), chargingNetworkManager)
      )
      expectNoMessage()
      beamVilleCar2.primaryFuelLevelInJoules should be(1.805E8)
      beamVilleCar3.primaryFuelLevelInJoules should be(1.08E8)

      chargingNetworkManager ! TriggerWithId(ChargingTimeOutTrigger(442, beamVilleCar2), 12)
      expectMsgType[StartingRefuelSession] should be(StartingRefuelSession(442, beamVilleCar3.id, 12))
      expectMsgType[CompletionNotice]
      expectNoMessage()
      beamVilleCar2.primaryFuelLevelInJoules should be(2.16E8)
      beamVilleCar2.isConnectedToChargingPoint() should be(false)
      beamVilleCar3.primaryFuelLevelInJoules should be(1.08E8)

      chargingNetworkManager ! TriggerWithId(PlanEnergyDispatchTrigger(600), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(ChargingTimeOutTrigger(874, beamVilleCar3), chargingNetworkManager),
        ScheduleTrigger(PlanEnergyDispatchTrigger(900), chargingNetworkManager)
      )
      expectNoMessage()
      beamVilleCar2.primaryFuelLevelInJoules should be(2.16E8)
      beamVilleCar3.primaryFuelLevelInJoules should be(1.475E8)

      chargingNetworkManager ! TriggerWithId(ChargingTimeOutTrigger(874, beamVilleCar3), 0)
      expectMsgType[CompletionNotice]
      expectNoMessage()
      beamVilleCar2.primaryFuelLevelInJoules should be(2.16E8)
      beamVilleCar3.primaryFuelLevelInJoules should be(2.16E8)
      beamVilleCar3.isConnectedToChargingPoint() should be(false)
    }
  }

  override def beforeEach(): Unit = {
    scheduler = TestActorRef[BeamAgentSchedulerRedirect](
      Props(
        new BeamAgentSchedulerRedirect(
          beamConfig,
          900,
          10,
          new StuckFinder(beamConfig.beam.debug.stuckAgentDetection)
        )
      )
    )
    parkingManager = new TestProbe(system)
    chargingNetworkManager = TestActorRef[ChargingNetworkManager](
      ChargingNetworkManager.props(
        beamServices,
        ParkingAndChargingInfrastructure(beamServices, envelopeInUTM),
        parkingManager.ref,
        scheduler
      )
    )
    personAgent = new TestProbe(system)

    chargingNetworkManager ! TriggerWithId(InitializeTrigger(0), 0)
    expectMsgType[ScheduleTrigger] shouldBe ScheduleTrigger(PlanEnergyDispatchTrigger(0), chargingNetworkManager)
    expectMsgType[CompletionNotice]
    expectNoMessage()
  }

  override def afterEach(): Unit = {
    val beamVilleCar = beamScenario.privateVehicles(Id.create(2, classOf[BeamVehicle]))
    beamVilleCar.resetState()
    beamVilleCar.disconnectFromChargingPoint()
    beamVilleCar.unsetParkingStall()
    beamVilleCar.addFuel(beamVilleCar.beamVehicleType.primaryFuelCapacityInJoule)
    scheduler ! Finish
    chargingNetworkManager ! Finish
    parkingManager.ref ! Finish
    personAgent.ref ! Finish
  }
}
