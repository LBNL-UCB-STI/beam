package beam.integration

import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.{Config, ConfigFactory, ConfigValueFactory}

trait IntegrationSpecCommon {
  private val LAST_ITER_CONF_PATH = "matsim.modules.controler.lastIteration"

  protected var totalIterations: Int = 1

  val configFileName = "test/input/beamville/beam.conf"

  val configLocation = ConfigFactory.parseString("config=" + configFileName)

  lazy val baseConfig: Config = testConfig(configFileName)
    .resolve()
    .withValue("beam.outputs.events.fileOutputFormats", ConfigValueFactory.fromAnyRef("xml"))
    .withValue(LAST_ITER_CONF_PATH, ConfigValueFactory.fromAnyRef(totalIterations - 1))
    .withFallback(configLocation)
    .resolve

  def isOrdered[A](s: Seq[A])(cf: (A, A) => Boolean): Boolean = {
    val z1 = s.drop(1)
    val z2 = s.dropRight(1)
    val zip = z2 zip z1

    zip.forall { case (a, b) => cf(a, b) }
  }

}
