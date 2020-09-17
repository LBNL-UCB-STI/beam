package beam.agentsim.infrastructure

import akka.actor.{ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit}
import beam.agentsim.agents.PersonAgent
import beam.agentsim.agents.modalbehaviors.DrivesVehicle._
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.infrastructure.ChargingNetworkManager.PlanningTimeOutTrigger
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.scheduler.BeamAgentScheduler
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.sim.config.BeamConfig
import beam.sim.{BeamHelper, BeamServices}
import beam.utils.TestConfigUtils.testConfig
import beam.utils.{DateUtils, StuckFinder}
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.Id
import org.mockito.Mockito.when
import org.scalatest.{BeforeAndAfterEach, Matchers, WordSpecLike}
import org.scalatestplus.mockito.MockitoSugar

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

  private val filesPath = getClass.getResource("/files").getPath
  private val beamConfig: BeamConfig = BeamConfig(
    system.settings.config
      .withFallback(ConfigFactory.parseString(s"""
           |beam.agentsim.agents.vehicles.vehicleTypesFilePath = $filesPath"/vehicleTypes-simple.csv"
           |beam.agentsim.agents.vehicles.vehiclesFilePath = $filesPath"/vehicles-simple.csv"
           |""".stripMargin))
      .withFallback(testConfig("test/input/beamville/beam.conf").resolve())
  )

  private val beamServices: BeamServices = mock[BeamServices]

  private val beamScenario = loadScenario(beamConfig)
    .copy(fuelTypePrices = Map().withDefaultValue(0.0)) // Reset fuel prices to 0 so we get pure monetary costs

  private val beamVilleCar = beamScenario.privateVehicles(Id.create(2, classOf[BeamVehicle]))

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
      chargingNetworkManager ! ChargingPlugRequest(beamVilleCar, personAgent)

      chargingNetworkManager ! TriggerWithId(PlanningTimeOutTrigger(0), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(PlanningTimeOutTrigger(300), chargingNetworkManager)
      )
      expectNoMessage()
    }
    "add a vehicle to charging queue with a little fuel required and won't be charged" in {
      beamVilleCar.addFuel(-100)
      beamVilleCar.connectToChargingPoint(0)
      beamVilleCar.useParkingStall(parkingStall)

      chargingNetworkManager ! ChargingPlugRequest(beamVilleCar, personAgent)

      chargingNetworkManager ! TriggerWithId(PlanningTimeOutTrigger(0), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(EndRefuelSessionTrigger(0, 0, 0.0, beamVilleCar), personAgent),
        ScheduleTrigger(PlanningTimeOutTrigger(300), chargingNetworkManager)
      )
      expectNoMessage()
    }
    "add a vehicle to charging queue with some fuel required and will charge" in {
      beamVilleCar.addFuel(-1e7)
      beamVilleCar.connectToChargingPoint(0)
      beamVilleCar.useParkingStall(parkingStall)

      chargingNetworkManager ! ChargingPlugRequest(beamVilleCar, personAgent)

      chargingNetworkManager ! TriggerWithId(PlanningTimeOutTrigger(0), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(EndRefuelSessionTrigger(233, 0, 1.0019E7, beamVilleCar), personAgent),
        ScheduleTrigger(PlanningTimeOutTrigger(300), chargingNetworkManager)
      )
      expectNoMessage()
    }
    "add a vehicle to charging queue with a lot fuel required and will charge in 2 cycles" in {
      beamVilleCar.addFuel(-1.5e7)
      beamVilleCar.connectToChargingPoint(0)
      beamVilleCar.useParkingStall(parkingStall)

      chargingNetworkManager ! ChargingPlugRequest(beamVilleCar, personAgent)

      chargingNetworkManager ! TriggerWithId(PlanningTimeOutTrigger(300), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(PlanningTimeOutTrigger(600), chargingNetworkManager)
      )
      expectNoMessage()

      chargingNetworkManager ! TriggerWithId(PlanningTimeOutTrigger(600), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(EndRefuelSessionTrigger(649, 0, 1.5006999999999998E7, beamVilleCar), personAgent),
        ScheduleTrigger(PlanningTimeOutTrigger(900), chargingNetworkManager)
      )
      expectNoMessage()
    }
    "add a vehicle to charging queue with a lot fuel required but unplug event happens before 1st cycle" in {
      beamVilleCar.addFuel(-1.5e7)
      beamVilleCar.connectToChargingPoint(0)
      beamVilleCar.useParkingStall(parkingStall)

      chargingNetworkManager ! ChargingPlugRequest(beamVilleCar, personAgent)

      chargingNetworkManager ! ChargingUnplugRequest(beamVilleCar, 15)
      expectMsgType[ScheduleTrigger].trigger shouldBe EndRefuelSessionTrigger(15, 0, 645000, beamVilleCar)
      expectNoMessage()

      chargingNetworkManager ! TriggerWithId(PlanningTimeOutTrigger(0), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(PlanningTimeOutTrigger(300), chargingNetworkManager)
      )
      expectNoMessage()
    }
    "add a vehicle to charging queue with a lot fuel required but unplug event happens after 1st cycle" in {
      beamVilleCar.addFuel(-1.5e7)
      beamVilleCar.connectToChargingPoint(0)
      beamVilleCar.useParkingStall(parkingStall)

      chargingNetworkManager ! ChargingPlugRequest(beamVilleCar, personAgent)

      chargingNetworkManager ! TriggerWithId(PlanningTimeOutTrigger(600), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(PlanningTimeOutTrigger(900), chargingNetworkManager)
      )
      expectNoMessage()

      chargingNetworkManager ! ChargingUnplugRequest(beamVilleCar, 615)
      expectMsgType[ScheduleTrigger].trigger shouldBe EndRefuelSessionTrigger(615, 0, 645000, beamVilleCar)
      expectNoMessage()

      chargingNetworkManager ! TriggerWithId(PlanningTimeOutTrigger(900), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(PlanningTimeOutTrigger(1200), chargingNetworkManager)
      )
      expectNoMessage()
    }
    "add a vehicle to charging queue with a lot fuel required but unplug event happens after 2nd cycle" in {
      beamVilleCar.addFuel(-1.5e7)
      beamVilleCar.connectToChargingPoint(0)
      beamVilleCar.useParkingStall(parkingStall)

      chargingNetworkManager ! ChargingPlugRequest(beamVilleCar, personAgent)

      chargingNetworkManager ! TriggerWithId(PlanningTimeOutTrigger(600), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(PlanningTimeOutTrigger(900), chargingNetworkManager)
      )
      expectNoMessage()

      chargingNetworkManager ! TriggerWithId(PlanningTimeOutTrigger(900), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(EndRefuelSessionTrigger(949, 0, 1.5006999999999998E7, beamVilleCar), personAgent),
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
