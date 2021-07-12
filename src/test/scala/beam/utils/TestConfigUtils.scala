package beam.utils

import beam.sim.{BeamHelper, BeamServices}
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import com.typesafe.config.{Config, ConfigFactory, ConfigValueFactory}
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}

object TestConfigUtils extends BeamHelper {
  val testOutputDir = "output/test/"

  val configFileName = "test/input/beamville/beam.conf"
  val configLocation: Config = ConfigFactory.parseString("config=" + configFileName)

  val minimumValidBeamConfig: Config = {
    ConfigFactory.parseString(
      """|beam.agentsim.agents.vehicles.sharedFleets=[]
         |beam.debug.stuckAgentDetection.thresholds=[]
         |matsim.modules.strategy.parameterset=[]
         |matsim.modules.planCalcScore.parameterset=[]
         |""".stripMargin
    )
  }

  def testConfig(conf: String): Config =
    BeamConfigUtils
      .parseFileSubstitutingInputDirectory(conf)
      .withValue("beam.outputs.baseOutputDirectory", ConfigValueFactory.fromAnyRef(testOutputDir))
      .withFallback(configLocation)

  def configToBeamServices(baseConfig: Config): BeamServices = {
    val beamConfig = BeamConfig(baseConfig)
    val beamScenario = loadScenario(beamConfig)
    val configBuilder = new MatSimBeamConfigBuilder(baseConfig)
    val matsimConfig = configBuilder.buildMatSimConf()
    FileUtils.setConfigOutputFile(beamConfig, matsimConfig)

    val scenario = ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]
    scenario.setNetwork(beamScenario.network)
    val injector = org.matsim.core.controler.Injector.createInjector(
      scenario.getConfig,
      module(baseConfig, beamConfig, scenario, beamScenario)
    )

    val beamServices: BeamServices = injector.getInstance(classOf[BeamServices])

    generatePopulationForPayloadPlans(
      beamConfig,
      beamServices.geo,
      beamScenario,
      scenario.getPopulation,
      scenario.getHouseholds
    )

    beamServices
  }
}
