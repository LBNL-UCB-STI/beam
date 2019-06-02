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
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito._
import org.scalatest.FlatSpec
import org.scalatest.mockito.MockitoSugar

class ThreeIterationsSpec extends FlatSpec with BeamHelper with MockitoSugar {

  it should "be able to run for three iterations without exceptions" in {
    val config = testConfig("test/input/beamville/beam.conf")
      .resolve()
      .withValue("beam.outputs.events.fileOutputFormats", ConfigValueFactory.fromAnyRef("xml,csv"))
      .resolve()
    val configBuilder = new MatSimBeamConfigBuilder(config)
    val matsimConfig = configBuilder.buildMatSimConf()
    matsimConfig.controler().setLastIteration(2)
    matsimConfig.planCalcScore().setMemorizingExperiencedPlans(true)
    val beamConfig = BeamConfig(config)
    FileUtils.setConfigOutputFile(beamConfig, matsimConfig)
    val beamScenario = loadScenario(beamConfig)
    val scenario = ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]
    scenario.setNetwork(beamScenario.network)

    val iterationCounter = mock[IterationEndsListener]
    val injector = org.matsim.core.controler.Injector.createInjector(
      scenario.getConfig,
      new AbstractModule() {
        override def install(): Unit = {
          install(module(config, scenario, beamScenario))
          addControlerListenerBinding().toInstance(iterationCounter)
        }
      }
    )
    val popAdjustment = DefaultPopulationAdjustment

    val beamServices = injector.getInstance(classOf[BeamServices])
    val controller = beamServices.controler
    popAdjustment(beamServices).update(scenario)

    controller.run()

    verify(iterationCounter, times(3)).notifyIterationEnds(any())
  }

}
