package beam.integration

import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.{Config, ConfigValueFactory}

trait IntegrationSpecCommon {

  val configFileName = "test/input/beamville/beam.conf"
  val baseConfig: Config = testConfig(configFileName)
    .withValue("beam.outputs.events.fileOutputFormats", ConfigValueFactory.fromAnyRef("xml"))
    .resolve

  def isOrdered[A](s: Seq[A])(cf: (A, A) => Boolean): Boolean = {
    val z1 = s.drop(1)
    val z2 = s.dropRight(1)
    val zip = z2 zip z1

    zip.forall { case (a, b) => cf(a, b) }
  }

}

