package beam.utils

import beam.sim.BeamServices
import beam.sim.config.BeamConfig
import com.typesafe.config.{ConfigFactory, ConfigValueFactory}
import org.mockito.Mockito.{when, withSettings}
import org.scalatest.mockito.MockitoSugar

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
