package beam.utils

import akka.actor.ActorSystem
import akka.testkit.TestKit
import beam.sim.{BeamHelper, BeamScenario, BeamServices}
import beam.sim.config.BeamConfig
import com.google.inject.Injector
import org.matsim.core.config.Config
import org.matsim.core.scenario.MutableScenario
import org.scalatest.{Args, BeforeAndAfterAll, Status, Suite}

abstract class BeamTestKit(system: ActorSystem)
    extends TestKit(system)
    with Suite
    with BeforeAndAfterAll
    with BeamHelper {

  lazy val beamExecutionConfig: BeamExecutionConfig = setupBeamWithConfig(system.settings.config)
  lazy val beamConfig: BeamConfig = beamExecutionConfig.beamConfig
  lazy val matsimConfig: Config = beamExecutionConfig.matsimConfig
  lazy val beamScenario: BeamScenario = loadScenario(beamConfig)
  lazy val scenario: MutableScenario = buildScenarioFromMatsimConfig(matsimConfig, beamScenario)
  lazy val injector: Injector = buildInjector(system.settings.config, scenario, beamScenario)
  lazy val beamServices: BeamServices = buildBeamServices(injector, scenario)

  override def run(testName: Option[String], args: Args): Status

  override def afterAll: Unit = {
    TestKit.shutdownActorSystem(system)
  }

}
