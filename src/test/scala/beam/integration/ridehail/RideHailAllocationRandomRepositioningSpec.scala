package beam.integration.ridehail

import beam.router.r5.NetworkCoordinator
import beam.sim.{BeamHelper, BeamServices}
import beam.sim.config.BeamConfig
import beam.utils.FileUtils
import org.matsim.core.controler.AbstractModule
import org.matsim.core.controler.listener.IterationEndsListener
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito._
import org.scalatest.FlatSpec
import org.scalatest.mockito.MockitoSugar

class RideHailAllocationRandomRepositioningSpec extends FlatSpec with BeamHelper with MockitoSugar {

  it should "be able to run for 1 iteration without exceptions" in {
    val config = RideHailTestHelper.buildConfig

    val matsimConfig = RideHailTestHelper.buildMatsimConfig(config)

    val beamConfig = BeamConfig(config)

    FileUtils.setConfigOutputFile(beamConfig, matsimConfig)

    val networkCoordinator = new NetworkCoordinator(beamConfig)
    networkCoordinator.loadNetwork()

    val scenario = ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]
    scenario.setNetwork(networkCoordinator.network)

    val iterationCounter = mock[IterationEndsListener]
    val injector = org.matsim.core.controler.Injector.createInjector(
      scenario.getConfig,
      new AbstractModule() {
        override def install(): Unit = {
          install(module(config, scenario, networkCoordinator))
          addControlerListenerBinding().toInstance(iterationCounter)
        }
      }
    )

    val controller = injector.getInstance(classOf[BeamServices]).controler
    controller.run()

    verify(iterationCounter, times(1)).notifyIterationEnds(any())
  }

}
