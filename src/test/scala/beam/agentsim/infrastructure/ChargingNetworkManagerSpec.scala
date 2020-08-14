package beam.agentsim.infrastructure

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit}
import beam.agentsim.infrastructure.ChargingNetworkManager.PlanningTimeOutTrigger
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.sim.config.BeamConfig
import beam.sim.{BeamHelper, BeamScenario, BeamServices}
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigFactory
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
          .withFallback(testConfig("test/input/beamville/beam.conf").resolve())
      )
    )
    with WordSpecLike
    with Matchers
    with BeamHelper
    with ImplicitSender
    with MockitoSugar
    with BeforeAndAfterAll {

  val beamConfig: BeamConfig = BeamConfig(system.settings.config)
  val beamServices: BeamServices = mock[BeamServices]

  var beamScenario: BeamScenario = _
  var chargingNetworkManager: ActorRef = _

  "ChargingNetworkManager" should {
    beamScenario = loadScenario(beamConfig)
      .copy(fuelTypePrices = Map().withDefaultValue(0.0)) // Reset fuel prices to 0 so we get pure monetary costs

    chargingNetworkManager = system.actorOf(
      Props(new ChargingNetworkManager(beamServices, beamScenario))
    )
    "process trigger event" in {
      val request = TriggerWithId(PlanningTimeOutTrigger(300), 0)
      chargingNetworkManager ! request

      val response = expectMsgType[CompletionNotice]
      response.newTriggers shouldBe Vector(ScheduleTrigger(PlanningTimeOutTrigger(600), chargingNetworkManager))
    }
  }
}
