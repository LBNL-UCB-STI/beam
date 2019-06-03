package beam.utils
import java.io.File

import beam.router.gtfs.FareCalculator
import beam.router.osm.TollCalculator
import beam.sim.BeamHelper
import beam.sim.common.GeoUtils
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting

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

  lazy val beamScenario = loadScenario(beamCfg)
  lazy val scenario = buildScenarioFromMatsimConfig(matsimConfig, beamScenario)
  lazy val injector = buildInjector(config, scenario, beamScenario)

  def fareCalculator: FareCalculator = injector.getInstance(classOf[FareCalculator])
  def tollCalculator: TollCalculator = injector.getInstance(classOf[TollCalculator])
  def geoUtil: GeoUtils = injector.getInstance(classOf[GeoUtils])
  def networkHelper: NetworkHelper = injector.getInstance(classOf[NetworkHelper])
}
