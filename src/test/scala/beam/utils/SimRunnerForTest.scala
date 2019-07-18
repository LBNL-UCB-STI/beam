package beam.utils
import java.io.File

import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.{BeamHelper, BeamScenario, BeamServices, BeamServicesImpl}
import com.google.inject.Injector
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting
import org.matsim.core.scenario.MutableScenario
import org.scalatest.{BeforeAndAfterAll, Suite}

trait SimRunnerForTest extends BeamHelper with BeforeAndAfterAll { this: Suite =>
  def config: com.typesafe.config.Config
  def basePath: String = new File("").getAbsolutePath
  def testOutputDir: String = TestConfigUtils.testOutputDir
  def outputDirPath: String

  // Next things are pretty cheap in initialization, so let it be non-lazy
  val beamConfig = BeamConfig(config)
  val matsimConfig = new MatSimBeamConfigBuilder(config).buildMatSimConf()
  matsimConfig.controler.setOutputDirectory(outputDirPath)
  matsimConfig.controler.setOverwriteFileSetting(OverwriteFileSetting.overwriteExistingFiles)

  var beamScenario: BeamScenario = _
  var scenario: MutableScenario = _
  var injector: Injector = _
  var services: BeamServices = _

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    beamScenario = loadScenario(beamConfig)
    scenario = buildScenarioFromMatsimConfig(matsimConfig, beamScenario)
    injector = buildInjector(config, beamConfig, scenario, beamScenario)
    services = new BeamServicesImpl(injector)
    services.modeChoiceCalculatorFactory = ModeChoiceCalculator(
      services.beamConfig.beam.agentsim.agents.modalBehaviors.modeChoiceClass,
      services
    )
  }

  override protected def afterAll(): Unit = {
    beamScenario = null
    scenario = null
    injector = null
    services = null
    super.afterAll()
  }
}
