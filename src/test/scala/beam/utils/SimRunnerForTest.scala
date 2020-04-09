package beam.utils
import java.io.File
import java.util.UUID

import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator
import beam.sim.config.{BeamConfig, BeamConfigHolder, MatSimBeamConfigBuilder}
import beam.sim.{BeamHelper, BeamScenario, BeamServices, BeamServicesImpl}
import com.google.inject.Injector
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting
import org.matsim.core.scenario.MutableScenario
import org.scalatest.{BeforeAndAfterAll, Suite}

case class TestContext(iterationActorName: String, transitSystemActorName: String, parkingManagerActorName: String) {
  def transitSystemActorFullPath: String = s"/user/$iterationActorName/$transitSystemActorName"
}

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
      services,
      injector.getInstance[BeamConfigHolder](classOf[BeamConfigHolder])
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
    super.afterAll()
  }

  def nextIterationActorName: String = {
    s"BeamMobsim.iteration-${UUID.randomUUID()}"
  }

  def nextTransitSystemActorName: String = {
    s"transit-system-${UUID.randomUUID()}"
  }

  def nextParkingManagerActorName: String = {
    s"ParkingManager-${UUID.randomUUID()}"
  }

  def getNextTestContext: TestContext =
    TestContext(nextIterationActorName, nextTransitSystemActorName, nextParkingManagerActorName)
}
