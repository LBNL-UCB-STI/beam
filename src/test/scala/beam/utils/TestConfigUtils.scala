package beam.utils

import com.typesafe.config.ConfigValueFactory

object TestConfigUtils {
  def testConfig(conf: String) = BeamConfigUtils.parseFileSubstitutingInputDirectory(conf).withValue("beam.outputs.baseOutputDirectory", ConfigValueFactory.fromAnyRef("output/test")).resolve()
}
