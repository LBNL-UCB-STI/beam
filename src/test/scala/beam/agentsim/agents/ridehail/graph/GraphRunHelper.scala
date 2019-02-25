package beam.agentsim.agents.ridehail.graph
import beam.router.r5.DefaultNetworkCoordinator
import beam.sim.{BeamHelper, BeamServices}
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.population.DefaultPopulationAdjustment
import beam.utils.{FileUtils, NetworkHelper, NetworkHelperImpl}
import com.typesafe.config.Config
import org.matsim.core.controler.AbstractModule
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}

object GraphRunHelper {

  def apply(childModule: AbstractModule, baseConfig: Config): GraphRunHelper =
    new GraphRunHelper(childModule, baseConfig)
}

class GraphRunHelper(childModule: AbstractModule, baseConfig: Config) extends BeamHelper {

  private val beamConfig = BeamConfig(baseConfig)
  private val configBuilder = new MatSimBeamConfigBuilder(baseConfig)
  private val matsimConfig = configBuilder.buildMatSamConf()

  matsimConfig.planCalcScore().setMemorizingExperiencedPlans(true)
  FileUtils.setConfigOutputFile(beamConfig, matsimConfig)

  private val networkCoordinator = new DefaultNetworkCoordinator(beamConfig)
  networkCoordinator.loadNetwork()
  networkCoordinator.convertFrequenciesToTrips()

  private val scenario =
    ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]
  scenario.setNetwork(networkCoordinator.network)

  private val networkHelper: NetworkHelper = new NetworkHelperImpl(networkCoordinator.network)

  private lazy val injector = org.matsim.core.controler.Injector.createInjector(
    scenario.getConfig,
    module(baseConfig, scenario, networkCoordinator, networkHelper),
    childModule
  )

  private lazy val beamServices: BeamServices = injector.getInstance(classOf[BeamServices])

  def run(): Unit = {
    val popAdjustment = DefaultPopulationAdjustment
    popAdjustment(beamServices).update(scenario)
    beamServices.controler.run()
  }

}
