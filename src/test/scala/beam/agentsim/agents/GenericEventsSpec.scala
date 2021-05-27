package beam.agentsim.agents

import beam.integration.IntegrationSpecCommon
import beam.sim.{BeamHelper, BeamServices}
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.population.DefaultPopulationAdjustment
import beam.utils.{FileUtils, NetworkHelper}
import com.google.inject
import org.matsim.api.core.v01.Scenario
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.wordspec.AnyWordSpecLike

trait GenericEventsSpec extends AnyWordSpecLike with IntegrationSpecCommon with BeamHelper with BeforeAndAfterAll {

  protected var beamServices: BeamServices = _
  protected var eventManager: EventsManager = _
  protected var scenario: Scenario = _
  protected var networkHelper: NetworkHelper = _
  private var injector: inject.Injector = _

  override def beforeAll(): Unit = {
    val beamConfig = BeamConfig(baseConfig)
    val beamScenario = loadScenario(beamConfig)
    val configBuilder = new MatSimBeamConfigBuilder(baseConfig)
    val matsimConfig = configBuilder.buildMatSimConf()
    matsimConfig.planCalcScore().setMemorizingExperiencedPlans(true)
    FileUtils.setConfigOutputFile(beamConfig, matsimConfig)

    val scenario = ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]
    scenario.setNetwork(beamScenario.network)

    injector = org.matsim.core.controler.Injector.createInjector(
      matsimConfig,
      module(baseConfig, beamConfig, scenario, beamScenario)
    )

    beamServices = injector.getInstance(classOf[BeamServices])

    val popAdjustment = DefaultPopulationAdjustment(beamServices)
    popAdjustment.update(scenario)

    eventManager = injector.getInstance(classOf[EventsManager])
    networkHelper = injector.getInstance(classOf[NetworkHelper])
  }

  override protected def afterAll(): Unit = {
    val travelDistanceStats = injector.getInstance(classOf[org.matsim.analysis.TravelDistanceStats])
    if (travelDistanceStats != null)
      travelDistanceStats.close()
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
