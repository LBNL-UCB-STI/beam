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
import org.scalatest.flatspec.AnyFlatSpec

class RideHailReplaceAllocationSpec extends AnyFlatSpec with BeamHelper {

  // dummy change
  // TODO: include events handling as with : RideHailPassengersEventsSpec
  it should "be able to run for 1 iteration without exceptions" ignore {
    val config = RideHailTestHelper.buildConfig(RideHailResourceAllocationManager.IMMEDIATE_DISPATCH_WITH_OVERWRITE)

    val matsimConfig = RideHailTestHelper.buildMatsimConfig(config)

    val beamConfig = BeamConfig(config)
    FileUtils.setConfigOutputFile(beamConfig, matsimConfig)
    val beamScenario = loadScenario(beamConfig)

    val scenario = ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]
    scenario.setNetwork(beamScenario.network)

    val iterationCounter = mock(classOf[IterationEndsListener])
    val injector = org.matsim.core.controler.Injector.createInjector(
      scenario.getConfig,
      new AbstractModule() {
        override def install(): Unit = {
          install(module(config, beamConfig, scenario, beamScenario, None))
          addControlerListenerBinding().toInstance(iterationCounter)
        }
      }
    )

    val services = injector.getInstance(classOf[BeamServices])
    DefaultPopulationAdjustment(services).update(scenario)
    val controller = services.controler
    controller.run()

    verify(iterationCounter, times(1)).notifyIterationEnds(any())
  }

}
