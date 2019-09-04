package beam.integration

import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.{Config, ConfigFactory, ConfigValueFactory}

trait IntegrationSpecCommon {
  protected var totalIterations: Int = 1

  val configFileName = "test/input/beamville/beam.conf"

  val configLocation = ConfigFactory.parseString("config=" + configFileName)

  lazy val baseConfig: Config = testConfig(configFileName)
    .resolve()
    .withValue("beam.outputs.events.fileOutputFormats", ConfigValueFactory.fromAnyRef("xml"))
    .withValue("matsim.modules.controler.lastIteration", ConfigValueFactory.fromAnyRef(totalIterations - 1))
    .withValue("beam.agentsim.lastIteration", ConfigValueFactory.fromAnyRef(totalIterations - 1))
    .withFallback(configLocation)
    .resolve

  def isOrdered[A](s: Seq[A])(cf: (A, A) => Boolean): Boolean = {
    val z1 = s.drop(1)
    val z2 = s.dropRight(1)
    val zip = z2 zip z1

    zip.forall { case (a, b) => cf(a, b) }
  }

}
