package beam.agentsim.infrastructure

import akka.actor.{ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit}
import beam.agentsim.agents.PersonAgent
import beam.agentsim.agents.modalbehaviors.DrivesVehicle._
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.infrastructure.ChargingNetworkManager.{ChargingTimeOutTrigger, PlanningTimeOutTrigger}
import beam.agentsim.infrastructure.charging.ChargingPointType
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
import org.mockito.Mockito.when
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
  val beamServices = new BeamServicesImpl(injector)
  private val beamVilleCar = beamScenario.privateVehicles(Id.create(2, classOf[BeamVehicle]))

  val timeStepInSeconds: Int = beamConfig.beam.agentsim.chargingNetworkManager.timeStepInSeconds

  def setBeamVilleCar(parkingStall: ParkingStall, fuelToAdd: Double = 0.0) = {
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
      case msg => testActor ! msg
    }
  }

  "ChargingNetworkManager" should {
    val parkingStall = mock[ParkingStall]
    when(parkingStall.chargingPointType).thenReturn(Some(ChargingPointType.ChargingStationType2))
    when(parkingStall.locationUTM).thenReturn(beamServices.beamScenario.tazTreeMap.getTAZs.head.coord)
    when(parkingStall.parkingZoneId).thenReturn(1)

    val scheduler = TestActorRef[BeamAgentSchedulerRedirect](
      Props(
        new BeamAgentSchedulerRedirect(
          beamConfig,
          900,
          10,
          new StuckFinder(beamConfig.beam.debug.stuckAgentDetection)
        )
      )
    )
    val personAgent = TestActorRef[PersonAgent](Props.empty)
    val chargingNetworkManager = TestActorRef[ChargingNetworkManager](
      Props(new ChargingNetworkManager(beamServices, beamScenario, scheduler))
    )

    "process trigger PlanningTimeOutTrigger" in {
      chargingNetworkManager ! TriggerWithId(PlanningTimeOutTrigger(0), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(PlanningTimeOutTrigger(300), chargingNetworkManager)
      )
    }

    "process the last trigger PlanningTimeOutTrigger" in {
      chargingNetworkManager ! TriggerWithId(PlanningTimeOutTrigger(DateUtils.getEndOfTime(beamConfig)), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector()
    }

    "add a vehicle to charging queue with full fuel level" in {
      setBeamVilleCar(parkingStall)
      chargingNetworkManager ! ChargingPlugRequest(beamVilleCar, personAgent)
      chargingNetworkManager ! TriggerWithId(PlanningTimeOutTrigger(0), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(EndRefuelSessionTrigger(0, 0, 0.0, beamVilleCar), personAgent),
        ScheduleTrigger(PlanningTimeOutTrigger(300), chargingNetworkManager)
      )
      expectNoMessage()
    }

    "add a vehicle to charging queue with a little fuel required and won't be charged" in {
      setBeamVilleCar(parkingStall, -100)
      chargingNetworkManager ! ChargingPlugRequest(beamVilleCar, personAgent)
      chargingNetworkManager ! TriggerWithId(PlanningTimeOutTrigger(0), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(EndRefuelSessionTrigger(0, 0, 0.0, beamVilleCar), personAgent),
        ScheduleTrigger(PlanningTimeOutTrigger(300), chargingNetworkManager)
      )
      expectNoMessage()
    }

    "add a vehicle to charging queue with some fuel required and will charge" in {
      setBeamVilleCar(parkingStall, -1e7)
      chargingNetworkManager ! ChargingPlugRequest(beamVilleCar, personAgent)
      chargingNetworkManager ! TriggerWithId(PlanningTimeOutTrigger(0), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(ChargingTimeOutTrigger(233, beamVilleCar.id), chargingNetworkManager),
        ScheduleTrigger(PlanningTimeOutTrigger(300), chargingNetworkManager)
      )
      expectNoMessage()

      chargingNetworkManager ! TriggerWithId(ChargingTimeOutTrigger(233, beamVilleCar.id), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(EndRefuelSessionTrigger(233, 0, 1.0019E7, beamVilleCar), personAgent)
      )
      expectNoMessage()
    }

    "add a vehicle to charging queue with a lot fuel required and will charge in 2 cycles" in {
      setBeamVilleCar(parkingStall, -1.5e7)
      chargingNetworkManager ! ChargingPlugRequest(beamVilleCar, personAgent)
      chargingNetworkManager ! TriggerWithId(PlanningTimeOutTrigger(300), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(PlanningTimeOutTrigger(600), chargingNetworkManager)
      )
      expectNoMessage()

      chargingNetworkManager ! TriggerWithId(PlanningTimeOutTrigger(600), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(ChargingTimeOutTrigger(649, beamVilleCar.id), chargingNetworkManager),
        ScheduleTrigger(PlanningTimeOutTrigger(900), chargingNetworkManager)
      )
      expectNoMessage()

      chargingNetworkManager ! TriggerWithId(ChargingTimeOutTrigger(649, beamVilleCar.id), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(EndRefuelSessionTrigger(649, 0, 1.5006999999999998E7, beamVilleCar), personAgent)
      )
      expectNoMessage()
    }

    "add a vehicle to charging queue with a lot fuel required but unplug event happens before 1st cycle" in {
      setBeamVilleCar(parkingStall, -1.5e7)
      chargingNetworkManager ! ChargingPlugRequest(beamVilleCar, personAgent)
      chargingNetworkManager ! ChargingUnplugRequest(beamVilleCar, 15)
      expectMsgType[ScheduleTrigger].trigger shouldBe EndRefuelSessionTrigger(15, 0, 645000.0, beamVilleCar)
      expectNoMessage()

      chargingNetworkManager ! TriggerWithId(PlanningTimeOutTrigger(0), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(PlanningTimeOutTrigger(300), chargingNetworkManager)
      )
      expectNoMessage()
    }

    "add a vehicle to charging queue with a lot fuel required but unplug event happens after 1st cycle" in {
      setBeamVilleCar(parkingStall, -1.5e7)
      chargingNetworkManager ! ChargingPlugRequest(beamVilleCar, personAgent)
      chargingNetworkManager ! TriggerWithId(PlanningTimeOutTrigger(600), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(PlanningTimeOutTrigger(900), chargingNetworkManager)
      )
      expectNoMessage()

      chargingNetworkManager ! ChargingUnplugRequest(beamVilleCar, 615)
      expectMsgType[ScheduleTrigger].trigger shouldBe EndRefuelSessionTrigger(
        615,
        0,
        1.3544999999999998E7,
        beamVilleCar
      )
      expectNoMessage()

      chargingNetworkManager ! TriggerWithId(PlanningTimeOutTrigger(900), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(PlanningTimeOutTrigger(1200), chargingNetworkManager)
      )
      expectNoMessage()
    }

    "add a vehicle to charging queue with a lot fuel required but unplug event happens after 2nd cycle" in {
      setBeamVilleCar(parkingStall, -0.1e7)
      chargingNetworkManager ! ChargingPlugRequest(beamVilleCar, personAgent)
      chargingNetworkManager ! TriggerWithId(PlanningTimeOutTrigger(600), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(ChargingTimeOutTrigger(623, beamVilleCar.id), chargingNetworkManager),
        ScheduleTrigger(PlanningTimeOutTrigger(900), chargingNetworkManager)
      )
      expectNoMessage()

      chargingNetworkManager ! TriggerWithId(PlanningTimeOutTrigger(900), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        //ScheduleTrigger(ChargingTimeOutTrigger(991, beamVilleCar.id), chargingNetworkManager),
        ScheduleTrigger(EndRefuelSessionTrigger(900, 0, 989000.0000000001, beamVilleCar), personAgent),
        ScheduleTrigger(PlanningTimeOutTrigger(1200), chargingNetworkManager)
      )
      expectNoMessage()

      chargingNetworkManager ! ChargingUnplugRequest(beamVilleCar, 915)
      expectNoMessage() // the vehicle is removed from queue already
    }
  }

  override def afterEach(): Unit = {
    beamVilleCar.resetState()
    beamVilleCar.disconnectFromChargingPoint()
    beamVilleCar.unsetParkingStall()
    beamVilleCar.addFuel(beamVilleCar.beamVehicleType.primaryFuelCapacityInJoule)
  }
}
