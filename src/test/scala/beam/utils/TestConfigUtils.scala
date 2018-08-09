package beam.utils

import com.typesafe.config.ConfigValueFactory

object TestConfigUtils {
  val testOutputDir = "output/test/"

  def testConfig(conf: String) =
    BeamConfigUtils
      .parseFileSubstitutingInputDirectory(conf)
      .withValue("beam.outputs.baseOutputDirectory", ConfigValueFactory.fromAnyRef(testOutputDir))
      .resolve()
}
