package beam.integration

import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.population.DefaultPopulationAdjustment
import beam.sim.{BeamHelper, BeamServices}
import beam.utils.FileUtils
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigValueFactory
import org.matsim.core.controler.AbstractModule
import org.matsim.core.controler.listener.IterationEndsListener
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.scalatestplus.mockito.MockitoSugar
import org.scalatest.{FlatSpec, Ignore}

@Ignore
class LCCMSpec extends FlatSpec with BeamHelper with MockitoSugar {

  it should "be able to run for three iterations with LCCM without exceptions" in {
    val config = testConfig("test/input/beamville/beam.conf")
      .resolve()
      .withValue("beam.outputs.events.fileOutputFormats", ConfigValueFactory.fromAnyRef("xml,csv"))
      .withValue(
        TestConstants.KEY_AGENT_MODAL_BEHAVIORS_MODE_CHOICE_CLASS,
        ConfigValueFactory.fromAnyRef("ModeChoiceLCCM")
      )
      .resolve()
    val configBuilder = new MatSimBeamConfigBuilder(config)
    val matsimConfig = configBuilder.buildMatSimConf()
    matsimConfig.controler().setLastIteration(2)
    matsimConfig.planCalcScore().setMemorizingExperiencedPlans(true)
    val beamConfig = BeamConfig(config)
    val beamScenario = loadScenario(beamConfig)
    FileUtils.setConfigOutputFile(beamConfig, matsimConfig)
    val scenario = ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]
    scenario.setNetwork(beamScenario.network)

    val iterationCounter = mock[IterationEndsListener]
    val injector = org.matsim.core.controler.Injector.createInjector(
      scenario.getConfig,
      new AbstractModule() {
        override def install(): Unit = {
          install(module(config, beamConfig, scenario, beamScenario))
          addControlerListenerBinding().toInstance(iterationCounter)
        }
      }
    )
    val popAdjustment = DefaultPopulationAdjustment

    val beamServices = injector.getInstance(classOf[BeamServices])
    val controller = beamServices.controler
    popAdjustment(beamServices).update(scenario)

    controller.run()
  }

}
