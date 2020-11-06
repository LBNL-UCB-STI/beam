package beam.agentsim.infrastructure

import akka.actor.Status.Success
import akka.actor.{ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.InitializeTrigger
import beam.agentsim.agents.vehicles.BeamVehicle
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
import org.scalatest.{BeforeAndAfterEach, Matchers, WordSpecLike}
import org.scalatestplus.mockito.MockitoSugar

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
    with WordSpecLike
    with Matchers
    with BeamHelper
    with ImplicitSender
    with MockitoSugar
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
                                               |  timeStepInSeconds = 300
                                               |  helicsFederateName = "CNMFederate"
                                               |  helicsDataOutStreamPoint = ""
                                               |  helicsDataInStreamPoint = ""
                                               |  helicsBufferSize = 1000
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

  def getBeamVilleCar(parkingStall: ParkingStall, fuelToSubtractInPercent: Double = 0.0): BeamVehicle = {
    val beamVilleCar = beamScenario.privateVehicles(Id.create(2, classOf[BeamVehicle]))
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

  val parkingStall: ParkingStall = ParkingStall(
    beamServices.beamScenario.tazTreeMap.getTAZs.head.tazId,
    0,
    beamServices.beamScenario.tazTreeMap.getTAZs.head.coord,
    0.0,
    Some(ChargingPointType.CustomChargingPoint("ultrafast", "250.0", "DC")),
    Some(PricingModel.FlatFee(0.0)),
    ParkingType.Workplace
  )
  var scheduler: TestActorRef[BeamAgentSchedulerRedirect] = _
  var parkingManager: TestActorRef[ParallelParkingManager] = _
  var personAgent: TestProbe = _
  var chargingNetworkManager: TestActorRef[ChargingNetworkManager] = _

  "ChargingNetworkManager" should {
    "process trigger PlanningTimeOutTrigger" in {
      chargingNetworkManager ! TriggerWithId(PlanningTimeOutTrigger(0), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(PlanningTimeOutTrigger(300), chargingNetworkManager)
      )
      expectNoMessage()
    }

    "process the last trigger PlanningTimeOutTrigger" in {
      chargingNetworkManager ! TriggerWithId(PlanningTimeOutTrigger(DateUtils.getEndOfTime(beamConfig)), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector()
      expectNoMessage()
    }

    "add a vehicle to charging queue with full fuel level but ends up with fuel added" in {
      val beamVilleCar = getBeamVilleCar(parkingStall)
      beamVilleCar.primaryFuelLevelInJoules should be(2.7E8)

      chargingNetworkManager ! ChargingPlugRequest(0, beamVilleCar, parkingStall, defaultVehicleManager)
      expectMsgType[Success]
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(2.7E8)

      chargingNetworkManager ! TriggerWithId(PlanningTimeOutTrigger(0), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(PlanningTimeOutTrigger(300), chargingNetworkManager)
      )
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(2.7E8)
    }

    "add a vehicle to charging queue with some fuel required and will charge" in {
      val beamVilleCar = getBeamVilleCar(parkingStall, 0.4)
      beamVilleCar.primaryFuelLevelInJoules should be(1.62E8)

      chargingNetworkManager ! ChargingPlugRequest(0, beamVilleCar, parkingStall, defaultVehicleManager)
      expectMsgType[Success]
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(1.62E8)

      chargingNetworkManager ! TriggerWithId(PlanningTimeOutTrigger(0), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(ChargingTimeOutTrigger(216, beamVilleCar, defaultVehicleManager), chargingNetworkManager),
        ScheduleTrigger(PlanningTimeOutTrigger(300), chargingNetworkManager)
      )
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(2.16E8)

      chargingNetworkManager ! TriggerWithId(ChargingTimeOutTrigger(216, beamVilleCar, defaultVehicleManager), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector()
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(2.16E8)
    }

    "add a vehicle to charging queue with a lot fuel required and will charge in 2 cycles" in {
      val beamVilleCar = getBeamVilleCar(parkingStall, 0.5)
      beamVilleCar.primaryFuelLevelInJoules should be(1.35E8)

      chargingNetworkManager ! ChargingPlugRequest(0, beamVilleCar, parkingStall, defaultVehicleManager)
      expectMsgType[Success]
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(1.35E8)

      chargingNetworkManager ! TriggerWithId(PlanningTimeOutTrigger(300), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(PlanningTimeOutTrigger(600), chargingNetworkManager)
      )
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(2.1E8)

      chargingNetworkManager ! TriggerWithId(PlanningTimeOutTrigger(600), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(ChargingTimeOutTrigger(624, beamVilleCar, defaultVehicleManager), chargingNetworkManager),
        ScheduleTrigger(PlanningTimeOutTrigger(900), chargingNetworkManager)
      )
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(2.16E8)

      chargingNetworkManager ! TriggerWithId(ChargingTimeOutTrigger(649, beamVilleCar, defaultVehicleManager), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector()
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(2.16E8)
    }

    "add a vehicle to charging queue with a lot fuel required but unplug event happens before 1st cycle" in {
      val beamVilleCar = getBeamVilleCar(parkingStall, 0.5)
      beamVilleCar.primaryFuelLevelInJoules should be(1.35E8)

      chargingNetworkManager ! ChargingPlugRequest(0, beamVilleCar, parkingStall, defaultVehicleManager)
      expectMsgType[Success]
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(1.35E8)

      chargingNetworkManager ! ChargingUnplugRequest(15, beamVilleCar, defaultVehicleManager)
      expectMsgType[EndRefuelSessionUponRequest] shouldBe EndRefuelSessionUponRequest(15, Some(beamVilleCar.id))
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(1.3875E8)

      chargingNetworkManager ! TriggerWithId(PlanningTimeOutTrigger(0), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(PlanningTimeOutTrigger(300), chargingNetworkManager)
      )
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(1.3875E8)
    }

    "add a vehicle to charging queue with a lot fuel required but unplug event happens after 1st cycle" in {
      val beamVilleCar = getBeamVilleCar(parkingStall, 0.5)
      beamVilleCar.primaryFuelLevelInJoules should be(1.35E8)

      chargingNetworkManager ! ChargingPlugRequest(0, beamVilleCar, parkingStall, defaultVehicleManager)
      expectMsgType[Success]
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(1.35E8)

      chargingNetworkManager ! TriggerWithId(PlanningTimeOutTrigger(600), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(PlanningTimeOutTrigger(900), chargingNetworkManager)
      )
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(2.1E8)

      chargingNetworkManager ! ChargingUnplugRequest(615, beamVilleCar, defaultVehicleManager)
      expectMsgType[EndRefuelSessionUponRequest] shouldBe EndRefuelSessionUponRequest(615, Some(beamVilleCar.id))
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(2.1375E8)

      chargingNetworkManager ! TriggerWithId(PlanningTimeOutTrigger(900), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(PlanningTimeOutTrigger(1200), chargingNetworkManager)
      )
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(2.1375E8)
    }

    "add a vehicle to charging queue with a lot fuel required but unplug event happens after 2nd cycle" in {
      val beamVilleCar = getBeamVilleCar(parkingStall, 0.5)
      beamVilleCar.primaryFuelLevelInJoules should be(1.35E8)

      chargingNetworkManager ! ChargingPlugRequest(0, beamVilleCar, parkingStall, defaultVehicleManager)
      expectMsgType[Success]
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(1.35E8)

      chargingNetworkManager ! TriggerWithId(PlanningTimeOutTrigger(600), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(PlanningTimeOutTrigger(900), chargingNetworkManager)
      )
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(2.1E8)

      chargingNetworkManager ! TriggerWithId(PlanningTimeOutTrigger(900), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(ChargingTimeOutTrigger(924, beamVilleCar, defaultVehicleManager), chargingNetworkManager),
        ScheduleTrigger(PlanningTimeOutTrigger(1200), chargingNetworkManager)
      )
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(2.16E8)

      chargingNetworkManager ! TriggerWithId(ChargingTimeOutTrigger(924, beamVilleCar, defaultVehicleManager), 0)
      expectMsgType[CompletionNotice] shouldBe CompletionNotice(0)
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(2.16E8)

      chargingNetworkManager ! ChargingUnplugRequest(1000, beamVilleCar, defaultVehicleManager)
      expectMsgType[EndRefuelSessionUponRequest] shouldBe EndRefuelSessionUponRequest(1000, None)
      beamVilleCar.primaryFuelLevelInJoules should be(2.16E8)
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
    parkingManager = TestActorRef[ParallelParkingManager](Props.empty)
    chargingNetworkManager = TestActorRef[ChargingNetworkManager](
      Props(new ChargingNetworkManager(beamServices, parkingManager, scheduler))
    )
    personAgent = new TestProbe(system)

    chargingNetworkManager ! TriggerWithId(InitializeTrigger(0), 0)
    expectMsgType[ScheduleTrigger] shouldBe ScheduleTrigger(PlanningTimeOutTrigger(0), chargingNetworkManager)
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
    parkingManager ! Finish
    personAgent.ref ! Finish
  }
}
