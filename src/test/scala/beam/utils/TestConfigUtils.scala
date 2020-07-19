package beam.utils

import com.typesafe.config.{Config, ConfigFactory, ConfigValueFactory}

object TestConfigUtils {
  val testOutputDir = "output/test/"

  val configFileName = "test/input/beamville/beam.conf"
  val configLocation: Config = ConfigFactory.parseString("config=" + configFileName)

  def testConfig(conf: String): Config =
    BeamConfigUtils
      .parseFileSubstitutingInputDirectory(conf)
      .withValue("beam.outputs.baseOutputDirectory", ConfigValueFactory.fromAnyRef(testOutputDir))
      .withFallback(configLocation)
}
