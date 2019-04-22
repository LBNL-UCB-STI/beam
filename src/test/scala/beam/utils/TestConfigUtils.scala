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

trait InjectableMock extends MockitoSugar {
  val injector = mock[com.google.inject.Injector](withSettings().stubOnly())
  val beamConfig: BeamConfig
  val beamSvc: BeamServices
  private val x = new beam.router.TravelTimeObserved(beamConfig, beamSvc)
  when(injector.getInstance(classOf[beam.router.TravelTimeObserved])).thenReturn(x)
}
