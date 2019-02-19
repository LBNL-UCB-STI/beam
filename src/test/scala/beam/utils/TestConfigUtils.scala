package beam.utils

import com.typesafe.config.{ConfigFactory, ConfigValueFactory}

object TestConfigUtils {
  val testOutputDir = "output/test/"

  val configFileName = "test/input/beamville/beam.conf"
  val configLocation = ConfigFactory.parseString("config=" + configFileName)

  def testConfig(conf: String) =
    BeamConfigUtils
      .parseFileSubstitutingInputDirectory(conf)
      .withValue("beam.outputs.baseOutputDirectory", ConfigValueFactory.fromAnyRef(testOutputDir))
      .withFallback(configLocation)
}
