package beam.agentsim.agents

import beam.integration.IntegrationSpecCommon
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.population.DefaultPopulationAdjustment
import beam.sim.{BeamHelper, BeamScenario, BeamServices}
import beam.utils.FileUtils
import org.matsim.api.core.v01.Scenario
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.scalatest.{BeforeAndAfterAll, WordSpecLike}

trait GenericEventsSpec extends WordSpecLike with IntegrationSpecCommon with BeamHelper with BeforeAndAfterAll {

  protected var beamServices: BeamServices = _
  protected var eventManager: EventsManager = _
  protected var scenario: Scenario = _

  override def beforeAll(): Unit = {

    val beamConfig = BeamConfig(baseConfig)
    val beamScenario = loadScenario(beamConfig)
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

    val popAdjustment = DefaultPopulationAdjustment(beamServices)
    popAdjustment.update(scenario)

    eventManager = injector.getInstance(classOf[EventsManager])
  }

  override protected def afterAll(): Unit = {
    scenario = null
    eventManager = null
    beamServices = null
    super.afterAll()
  }

  def processHandlers(eventHandlers: List[BasicEventHandler]): Unit = {
    for (eventHandler <- eventHandlers)
      eventManager.addHandler(eventHandler)

    beamServices.controler.run()
  }
}
