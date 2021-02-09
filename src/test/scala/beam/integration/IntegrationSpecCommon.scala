package beam.integration

import beam.utils.LoggingUtil
import beam.utils.TestConfigUtils.testConfig
import ch.qos.logback.classic.util.ContextInitializer
import com.typesafe.config.{Config, ConfigFactory, ConfigValueFactory}

trait IntegrationSpecCommon {

  setLogPathToTemporaryFolderUntilItIsInitializedProperlyByLoggingUtil()

  System.setProperty(ContextInitializer.CONFIG_FILE_PROPERTY, "logback-test.xml")

  protected var totalIterations: Int = 1

  private val configFileName = "test/input/beamville/beam.conf"

  private val configLocation = ConfigFactory.parseString("config=" + configFileName)

  protected def extensionConfig: Config = ConfigFactory.empty
  protected lazy val unResolvedBaseConfig: Config = {
    extensionConfig
      .withFallback(testConfig(configFileName))
      .resolve()
      .withValue("beam.outputs.collectAndCreateBeamAnalysisAndGraphs", ConfigValueFactory.fromAnyRef("true"))
      .withValue("beam.outputs.events.fileOutputFormats", ConfigValueFactory.fromAnyRef("xml"))
      .withValue("matsim.modules.controler.lastIteration", ConfigValueFactory.fromAnyRef(totalIterations - 1))
      .withValue("beam.agentsim.lastIteration", ConfigValueFactory.fromAnyRef(totalIterations - 1))
      .withFallback(configLocation)
      .resolve
  }
  protected lazy val baseConfig: Config = unResolvedBaseConfig.resolve

  def isOrdered[A](s: Seq[A])(cf: (A, A) => Boolean): Boolean = {
    val z1 = s.drop(1)
    val z2 = s.dropRight(1)
    val zip = z2 zip z1

    zip.forall { case (a, b) => cf(a, b) }
  }

  private def setLogPathToTemporaryFolderUntilItIsInitializedProperlyByLoggingUtil(): String = {
    System.setProperty(LoggingUtil.LOG_OUTPUT_DIRECTORY_KEY, System.getProperty("java.io.tmpdir"))
  }

}
