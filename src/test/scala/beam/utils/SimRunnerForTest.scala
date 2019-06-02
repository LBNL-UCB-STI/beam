package beam.utils
import java.io.File

import beam.router.gtfs.FareCalculator
import beam.router.osm.TollCalculator
import beam.sim.BeamHelper
import beam.sim.common.GeoUtils
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}

abstract class SimRunnerForTest extends BeamHelper {
  def config: com.typesafe.config.Config
  def basePath: String = new File("").getAbsolutePath
  def testOutputDir: String = TestConfigUtils.testOutputDir
  def outputDirPath: String

  // Next things are pretty cheap in initialization, so let it be non-lazy
  val beamCfg = BeamConfig(config)
  val matsimConfig = new MatSimBeamConfigBuilder(config).buildMatSimConf()
  matsimConfig.controler.setOutputDirectory(outputDirPath)
  matsimConfig.controler.setOverwriteFileSetting(OverwriteFileSetting.overwriteExistingFiles)
  def fareCalculator: FareCalculator = injector.getInstance(classOf[FareCalculator])
  def tollCalculator: TollCalculator = injector.getInstance(classOf[TollCalculator])
  def geoUtil: GeoUtils = injector.getInstance(classOf[GeoUtils])

  lazy val beamScenario = loadScenario(beamCfg)
  lazy val scenario = ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]
  lazy val networkHelper = new NetworkHelperImpl(beamScenario.network)
  lazy val injector = buildInjector(config, scenario, beamScenario)
}
