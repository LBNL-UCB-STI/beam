package beam.agentsim.infrastructure

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit}
import beam.agentsim.agents.PersonAgent
import beam.agentsim.agents.modalbehaviors.DrivesVehicle.ChargingPlugRequest
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
import beam.agentsim.infrastructure.ChargingNetworkManager.PlanningTimeOutTrigger
import beam.agentsim.scheduler.BeamAgentScheduler
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger, SchedulerProps, StartSchedule}
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.sim.config.BeamConfig
import beam.sim.{BeamHelper, BeamScenario, BeamServices}
import beam.utils.{DateUtils, StuckFinder}
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.Id
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
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
    with BeforeAndAfterAll {

  private val filesPath = getClass.getResource("/files").getPath
  private val beamConfig: BeamConfig = BeamConfig(
    system.settings.config
      .withFallback(ConfigFactory.parseString(s"""
           |beam.agentsim.agents.vehicles.vehiclesFilePath = $filesPath"/vehicles-simple.csv"
           |""".stripMargin))
      .withFallback(testConfig("test/input/beamville/beam.conf").resolve())
  )

  private val beamServices: BeamServices = mock[BeamServices]

  private var beamScenario: BeamScenario = _
  private var chargingNetworkManager: ActorRef = _

  "ChargingNetworkManager" should {
    beamScenario = loadScenario(beamConfig)
      .copy(fuelTypePrices = Map().withDefaultValue(0.0)) // Reset fuel prices to 0 so we get pure monetary costs

    val scheduler = TestActorRef[BeamAgentScheduler](
      SchedulerProps(
        beamConfig,
        stopTick = 900,
        maxWindow = 10,
        new StuckFinder(beamConfig.beam.debug.stuckAgentDetection)
      )
    )

    chargingNetworkManager = TestActorRef[ChargingNetworkManager](
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
    "add a vehicle to charging queue and charge it" ignore {
      val personAgent = TestActorRef[PersonAgent](
        Props.empty
      )

      val beamVilleCar = beamScenario.privateVehicles(Id.create(2, classOf[BeamVehicle]))

      chargingNetworkManager ! ChargingPlugRequest(beamVilleCar, personAgent)

      chargingNetworkManager ! TriggerWithId(PlanningTimeOutTrigger(0), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(PlanningTimeOutTrigger(300), chargingNetworkManager)
      )
    }
  }
}
