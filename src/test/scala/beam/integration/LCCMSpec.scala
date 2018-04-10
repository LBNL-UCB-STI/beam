package beam.integration

import beam.router.r5.NetworkCoordinator
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.{BeamHelper, BeamServices}
import beam.utils.{BeamConfigUtils, FileUtils}
import com.typesafe.config.ConfigValueFactory
import org.matsim.core.controler.AbstractModule
import org.matsim.core.controler.listener.IterationEndsListener
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.scalatest.FlatSpec
import org.scalatest.mockito.MockitoSugar

class LCCMSpec extends FlatSpec with BeamHelper with MockitoSugar {

  it should "be able to run for three iterations with LCCM without exceptions" in {
    val config = BeamConfigUtils.parseFileSubstitutingInputDirectory("test/input/beamville/beam.conf").resolve
      .withValue("beam.outputs.events.fileOutputFormats", ConfigValueFactory.fromAnyRef("xml,csv"))
      .withValue("beam.agentsim.agents.modalBehaviors.modeChoiceClass",  ConfigValueFactory.fromAnyRef("ModeChoiceLCCM"))
      .resolve()
    val configBuilder = new MatSimBeamConfigBuilder(config)
    val matsimConfig = configBuilder.buildMatSamConf()
    matsimConfig.controler().setLastIteration(2)
    matsimConfig.planCalcScore().setMemorizingExperiencedPlans(true)
    val beamConfig = BeamConfig(config)
    FileUtils.setConfigOutputFile(beamConfig, matsimConfig)
    val scenario = ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]
    val networkCoordinator = new NetworkCoordinator(beamConfig, scenario.getTransitVehicles)
    networkCoordinator.loadNetwork()
    scenario.setNetwork(networkCoordinator.network)
    val iterationCounter = mock[IterationEndsListener]
    val injector = org.matsim.core.controler.Injector.createInjector(scenario.getConfig, new AbstractModule() {
      override def install(): Unit = {
        install(module(config, scenario, networkCoordinator.transportNetwork))
        addControlerListenerBinding().toInstance(iterationCounter)
      }
    })
    val controler = injector.getInstance(classOf[BeamServices]).controler
    controler.run()
  }

}
