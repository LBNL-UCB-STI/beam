package beam.agentsim.agents

import beam.integration.IntegrationSpecCommon
import beam.router.r5.DefaultNetworkCoordinator
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.population.DefaultPopulationAdjustment
import beam.sim.{BeamHelper, BeamScenario, BeamServices}
import beam.utils.{FileUtils, MatsimServicesMock, NetworkHelper, NetworkHelperImpl}
import org.matsim.api.core.v01.Scenario
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.scalatest.{BeforeAndAfterAll, WordSpecLike}

trait GenericEventsSpec extends WordSpecLike with IntegrationSpecCommon with BeamHelper with BeforeAndAfterAll {

  protected var beamServices: BeamServices = _
  protected var beamScenario: BeamScenario = _
  protected var eventManager: EventsManager = _
  protected var scenario: Scenario = _

  override def beforeAll(): Unit = {

    val beamConfig = BeamConfig(baseConfig)
    beamScenario = loadScenario(beamConfig)
    val configBuilder = new MatSimBeamConfigBuilder(baseConfig)
    val matsimConfig = configBuilder.buildMatSimConf()
    matsimConfig.planCalcScore().setMemorizingExperiencedPlans(true)
    FileUtils.setConfigOutputFile(beamConfig, matsimConfig)

    val scenario = ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]
    scenario.setNetwork(beamScenario.network)

    val injector = org.matsim.core.controler.Injector.createInjector(
      matsimConfig,
      module(baseConfig, scenario, beamScenario)
    )

    beamServices = injector.getInstance(classOf[BeamServices])
    beamServices.matsimServices = new MatsimServicesMock(null, scenario)

    val popAdjustment = DefaultPopulationAdjustment(beamServices, injector.getInstance(classOf[BeamScenario]))
    popAdjustment.update(scenario)

    eventManager = injector.getInstance(classOf[EventsManager])
  }

  def processHandlers(eventHandlers: List[BasicEventHandler]): Unit = {
    for (eventHandler <- eventHandlers)
      eventManager.addHandler(eventHandler)

    beamServices.controler.run()
  }
}
