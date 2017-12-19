package beam.integration

import java.io.File

import beam.router.r5.NetworkCoordinator
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.{BeamServices, RunBeam}
import beam.utils.FileUtils
import com.typesafe.config.{ConfigFactory, ConfigValueFactory}
import org.matsim.core.controler.AbstractModule
import org.matsim.core.controler.listener.IterationEndsListener
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.scalatest.FlatSpec
import org.scalatest.mockito.MockitoSugar

class TwoIterationsSpec extends FlatSpec with RunBeam with MockitoSugar {

  it should "be able to run for two iterations without exceptions" in {
    val config = ConfigFactory.parseFile(new File("test/input/beamville/beam.conf")).resolve
      .withValue("beam.outputs.events.fileOutputFormats", ConfigValueFactory.fromAnyRef("xml,csv"))
      .resolve()
    val configBuilder = new MatSimBeamConfigBuilder(config)
    val matsimConfig = configBuilder.buildMatSamConf()
    matsimConfig.controler().setLastIteration(1)
    val beamConfig = BeamConfig(config)
    FileUtils.setConfigOutputFile(beamConfig.beam.outputs.outputDirectory, beamConfig.beam.agentsim.simulationName, matsimConfig)
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
    verify(iterationCounter, times(2)).notifyIterationEnds(any())
  }

}
