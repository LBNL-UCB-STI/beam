package beam.integration.ridehail

import beam.agentsim.agents.ridehail.allocation.RideHailResourceAllocationManager
import beam.sim.config.BeamConfig
import beam.sim.population.DefaultPopulationAdjustment
import beam.sim.{BeamHelper, BeamServices}
import beam.utils.FileUtils
import org.matsim.core.controler.AbstractModule
import org.matsim.core.controler.listener.IterationEndsListener
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito._
import org.scalatest.FlatSpec
import org.scalatestplus.mockito.MockitoSugar

class RideHailAllocationRandomRepositioningSpec extends FlatSpec with BeamHelper with MockitoSugar {

  it should "be able to run for 1 iteration without exceptions" in {
    // FIXME
    val config = RideHailTestHelper.buildConfig(RideHailResourceAllocationManager.DEFAULT_MANAGER)

    val matsimConfig = RideHailTestHelper.buildMatsimConfig(config)

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

    verify(iterationCounter, times(1)).notifyIterationEnds(any())
  }

}
