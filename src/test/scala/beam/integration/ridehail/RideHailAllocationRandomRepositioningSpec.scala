package beam.integration.ridehail

import beam.router.r5.NetworkCoordinator
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
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

class RideHailAllocationRandomRepositioningSpec
    extends FlatSpec
    with BeamHelper
    with MockitoSugar {

  it should "be able to run for 1 iteration without exceptions" in {
    val config = testConfig("test/input/beamville/beam.conf")
      .withValue("beam.outputs.events.fileOutputFormats",
                 ConfigValueFactory.fromAnyRef("xml,csv"))
      .withValue(
        "beam.agentsim.agents.rideHail.allocationManager.name",
        ConfigValueFactory.fromAnyRef("RANDOM_REPOSITIONING")
      )
      .withValue(
        "beam.agentsim.agents.modalBehaviors.modeChoiceClass",
        ConfigValueFactory.fromAnyRef("ModeChoiceRideHailIfAvailable")
      )
      .withValue(
        "beam.agentsim.agents.rideHail.numDriversAsFractionOfPopulation",
        ConfigValueFactory.fromAnyRef(0.1)
      )
      .resolve()
    val configBuilder = new MatSimBeamConfigBuilder(config)
    val matsimConfig = configBuilder.buildMatSamConf()
    matsimConfig.controler().setLastIteration(0)
    matsimConfig.planCalcScore().setMemorizingExperiencedPlans(true)
    val beamConfig = BeamConfig(config)
    FileUtils.setConfigOutputFile(beamConfig, matsimConfig)
    val scenario =
      ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]
    val networkCoordinator = new NetworkCoordinator(beamConfig)
    networkCoordinator.loadNetwork()
    scenario.setNetwork(networkCoordinator.network)
    val iterationCounter = mock[IterationEndsListener]
    val injector = org.matsim.core.controler.Injector.createInjector(
      scenario.getConfig,
      new AbstractModule() {
        override def install(): Unit = {
          install(module(config, scenario, networkCoordinator.transportNetwork))
          addControlerListenerBinding().toInstance(iterationCounter)
        }
      }
    )
    val controler = injector.getInstance(classOf[BeamServices]).controler
    controler.run()
    verify(iterationCounter, times(1)).notifyIterationEnds(any())
  }

}
