package beam.agentsim.agents

import beam.integration.IntegrationSpecCommon
import beam.router.r5.NetworkCoordinator
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.population.{DefaultPopulationAdjustment, PopulationAdjustment}
import beam.sim.{BeamHelper, BeamServices}
import beam.utils.FileUtils
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.scalatest.{BeforeAndAfterAll, WordSpecLike}

trait GenericEventsSpec extends WordSpecLike with IntegrationSpecCommon with BeamHelper with BeforeAndAfterAll {

  protected var beamServices: BeamServices = _
  protected var eventManager: EventsManager = _
  protected var networkCoordinator: NetworkCoordinator = _

  override def beforeAll(): Unit = {

    val beamConfig = BeamConfig(baseConfig)
    val configBuilder = new MatSimBeamConfigBuilder(baseConfig)
    val matsimConfig = configBuilder.buildMatSamConf()
    matsimConfig.planCalcScore().setMemorizingExperiencedPlans(true)
    FileUtils.setConfigOutputFile(beamConfig, matsimConfig)

    networkCoordinator = new NetworkCoordinator(beamConfig)
    networkCoordinator.loadNetwork()

    val scenario =
      ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]
    scenario.setNetwork(networkCoordinator.network)

    val injector = org.matsim.core.controler.Injector.createInjector(
      scenario.getConfig,
      module(baseConfig, scenario, networkCoordinator)
    )


    beamServices = injector.getInstance(classOf[BeamServices])
    val popAdjustment = DefaultPopulationAdjustment(beamServices)
    popAdjustment.update(scenario)

    eventManager = injector.getInstance(classOf[EventsManager])
  }

  def processHandlers(eventHandlers: List[BasicEventHandler]): Unit = {
    for (eventHandler <- eventHandlers)
      eventManager.addHandler(eventHandler)

    beamServices.controler.run()
  }
}
