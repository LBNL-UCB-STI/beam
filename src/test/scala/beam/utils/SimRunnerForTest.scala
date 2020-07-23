package beam.utils
import java.io.File

import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator
import beam.sim.config.{BeamConfig, BeamConfigHolder, MatSimBeamConfigBuilder}
import beam.sim.{BeamHelper, BeamScenario, BeamServices, BeamServicesImpl}
import com.google.inject.Injector
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.config.Config
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting
import org.matsim.core.events.EventsManagerImpl
import org.matsim.core.scenario.MutableScenario
import org.scalatest.{BeforeAndAfterAll, Suite}

trait SimRunnerForTest extends BeamHelper with BeforeAndAfterAll { this: Suite =>
  def config: com.typesafe.config.Config
  def basePath: String = new File("").getAbsolutePath
  def testOutputDir: String = TestConfigUtils.testOutputDir
  def outputDirPath: String

  // Next things are pretty cheap in initialization, so let it be non-lazy
  val beamConfig: BeamConfig = BeamConfig(config)
  val matsimConfig: Config = new MatSimBeamConfigBuilder(config).buildMatSimConf()
  matsimConfig.controler.setOutputDirectory(outputDirPath)
  matsimConfig.controler.setOverwriteFileSetting(OverwriteFileSetting.overwriteExistingFiles)

  var beamScenario: BeamScenario = _
  var scenario: MutableScenario = _
  var injector: Injector = _
  var services: BeamServices = _
  var eventsManager: EventsManager = _

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    beamScenario = loadScenario(beamConfig)
    scenario = buildScenarioFromMatsimConfig(matsimConfig, beamScenario)
    injector = buildInjector(config, beamConfig, scenario, beamScenario)
    services = new BeamServicesImpl(injector)
    eventsManager = new EventsManagerImpl
    services.modeChoiceCalculatorFactory = ModeChoiceCalculator(
      services.beamConfig.beam.agentsim.agents.modalBehaviors.modeChoiceClass,
      services,
      injector.getInstance[BeamConfigHolder](classOf[BeamConfigHolder]),
      eventsManager
    )
  }

  override protected def afterAll(): Unit = {
    val travelDistanceStats = injector.getInstance(classOf[org.matsim.analysis.TravelDistanceStats])
    if (travelDistanceStats != null)
      travelDistanceStats.close()
    beamScenario = null
    scenario = null
    injector = null
    services = null
    eventsManager = null
    super.afterAll()
  }
}
