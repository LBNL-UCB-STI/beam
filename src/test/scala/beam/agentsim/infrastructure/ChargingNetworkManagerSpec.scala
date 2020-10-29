package beam.agentsim.infrastructure

import akka.actor.{ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.infrastructure.ChargingNetworkManager._
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

  private val beamConfig: BeamConfig = BeamConfig(conf)
  private val matsimConfig = new MatSimBeamConfigBuilder(conf).buildMatSimConf()
  matsimConfig.controler.setOutputDirectory(TestConfigUtils.testOutputDir)
  matsimConfig.controler.setOverwriteFileSetting(OverwriteFileSetting.overwriteExistingFiles)
  private val beamScenario = loadScenario(beamConfig)
  private val scenario = buildScenarioFromMatsimConfig(matsimConfig, beamScenario)
  private val injector = buildInjector(system.settings.config, beamConfig, scenario, beamScenario)
  private val beamServices = new BeamServicesImpl(injector)
  private val beamVilleCar = beamScenario.privateVehicles(Id.create(2, classOf[BeamVehicle]))

  val timeStepInSeconds: Int = beamConfig.beam.agentsim.chargingNetworkManager.timeStepInSeconds

  def setBeamVilleCar(parkingStall: ParkingStall, fuelToAdd: Double = 0.0): BeamVehicle = {
    beamVilleCar.addFuel(fuelToAdd)
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
    1,
    beamServices.beamScenario.tazTreeMap.getTAZs.head.coord,
    0.0,
    Some(ChargingPointType.ChargingStationType2),
    Some(PricingModel.FlatFee(0.0)),
    ParkingType.Workplace
  )
  var scheduler: TestActorRef[BeamAgentSchedulerRedirect] = _
  var parkingManager: TestActorRef[ParallelParkingManager] = _
  var chargingNetworkManager: TestActorRef[ChargingNetworkManager] = _
  var personAgent: TestProbe = _

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
      setBeamVilleCar(parkingStall)
      beamVilleCar.primaryFuelLevelInJoules should be(2.7E8)

      chargingNetworkManager ! ChargingPlugRequest(0, beamVilleCar, personAgent.ref)
      chargingNetworkManager ! TriggerWithId(PlanningTimeOutTrigger(0), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(PlanningTimeOutTrigger(300), chargingNetworkManager)
      )
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(2.7E8)
    }

    "add a vehicle to charging queue with some fuel required and will charge" in {
      setBeamVilleCar(parkingStall, -1e7)
      beamVilleCar.primaryFuelLevelInJoules should be(2.6E8)

      chargingNetworkManager ! ChargingPlugRequest(0, beamVilleCar, personAgent.ref)
      chargingNetworkManager ! TriggerWithId(PlanningTimeOutTrigger(0), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(ChargingTimeOutTrigger(233, beamVilleCar.id), chargingNetworkManager),
        ScheduleTrigger(PlanningTimeOutTrigger(300), chargingNetworkManager)
      )
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(2.70019E8)

      chargingNetworkManager ! TriggerWithId(ChargingTimeOutTrigger(233, beamVilleCar.id), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector()
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(2.70019E8)
    }

    "add a vehicle to charging queue with a lot fuel required and will charge in 2 cycles" in {
      setBeamVilleCar(parkingStall, -1.5e7)
      beamVilleCar.primaryFuelLevelInJoules should be(2.55E8)

      chargingNetworkManager ! ChargingPlugRequest(0, beamVilleCar, personAgent.ref)
      chargingNetworkManager ! TriggerWithId(PlanningTimeOutTrigger(300), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(PlanningTimeOutTrigger(600), chargingNetworkManager)
      )
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(2.679E8)

      chargingNetworkManager ! TriggerWithId(PlanningTimeOutTrigger(600), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(ChargingTimeOutTrigger(649, beamVilleCar.id), chargingNetworkManager),
        ScheduleTrigger(PlanningTimeOutTrigger(900), chargingNetworkManager)
      )
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(2.70007E8)

      chargingNetworkManager ! TriggerWithId(ChargingTimeOutTrigger(649, beamVilleCar.id), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector()
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(2.70007E8)
    }

    "add a vehicle to charging queue with a lot fuel required but unplug event happens before 1st cycle" in {
      setBeamVilleCar(parkingStall, -1.5e7)
      beamVilleCar.primaryFuelLevelInJoules should be(2.55E8)

      chargingNetworkManager ! ChargingPlugRequest(0, beamVilleCar, personAgent.ref)
      chargingNetworkManager ! ChargingUnplugRequest(15, beamVilleCar, personAgent.ref)

      personAgent.expectMsgType[EndRefuelSessionUponRequest] shouldBe EndRefuelSessionUponRequest(15, beamVilleCar)
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(2.55645E8)

      chargingNetworkManager ! TriggerWithId(PlanningTimeOutTrigger(0), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(PlanningTimeOutTrigger(300), chargingNetworkManager)
      )
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(2.55645E8)
    }

    "add a vehicle to charging queue with a lot fuel required but unplug event happens after 1st cycle" in {
      setBeamVilleCar(parkingStall, -1.5e7)
      beamVilleCar.primaryFuelLevelInJoules should be(2.55E8)

      chargingNetworkManager ! ChargingPlugRequest(0, beamVilleCar, personAgent.ref)
      chargingNetworkManager ! TriggerWithId(PlanningTimeOutTrigger(600), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(PlanningTimeOutTrigger(900), chargingNetworkManager)
      )
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(2.679E8)

      chargingNetworkManager ! ChargingUnplugRequest(615, beamVilleCar, personAgent.ref)
      personAgent.expectMsgType[EndRefuelSessionUponRequest] shouldBe EndRefuelSessionUponRequest(615, beamVilleCar)
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(2.68545E8)

      chargingNetworkManager ! TriggerWithId(PlanningTimeOutTrigger(900), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(PlanningTimeOutTrigger(1200), chargingNetworkManager)
      )
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(2.68545E8)
    }

    "add a vehicle to charging queue with a lot fuel required but unplug event happens after 2nd cycle" in {
      setBeamVilleCar(parkingStall, -0.1e7)
      chargingNetworkManager ! ChargingPlugRequest(0, beamVilleCar, personAgent.ref)
      chargingNetworkManager ! TriggerWithId(PlanningTimeOutTrigger(600), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(ChargingTimeOutTrigger(623, beamVilleCar.id), chargingNetworkManager),
        ScheduleTrigger(PlanningTimeOutTrigger(900), chargingNetworkManager)
      )
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(2.69989E8)

      chargingNetworkManager ! TriggerWithId(PlanningTimeOutTrigger(900), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(PlanningTimeOutTrigger(1200), chargingNetworkManager)
      )
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(2.69989E8)

      chargingNetworkManager ! ChargingUnplugRequest(915, beamVilleCar, personAgent.ref)
      personAgent.expectNoMessage()
      expectNoMessage() // the vehicle is removed from queue already
      beamVilleCar.primaryFuelLevelInJoules should be(2.69989E8)
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
      Props(new ChargingNetworkManager(beamServices, beamScenario, parkingManager, scheduler))
    )
    personAgent = new TestProbe(system)
  }

  override def afterEach(): Unit = {
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
