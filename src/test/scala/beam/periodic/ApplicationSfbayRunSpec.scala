package beam.periodic

import java.nio.file.Paths
import beam.sim.BeamHelper
import beam.tags.{ExcludeRegular, Periodic}
import beam.utils.BeamConfigUtils
import com.typesafe.config.{Config, ConfigValueFactory}
import org.scalatest.matchers.should.Matchers
import org.scalatest.{Assertion, BeforeAndAfterAllConfigMap, ConfigMap}
import org.scalatest.wordspec.AnyWordSpecLike

import java.io.File

class ApplicationSfbayRunSpec extends AnyWordSpecLike with Matchers with BeforeAndAfterAllConfigMap with BeamHelper {

  private val ITERS_DIR = "ITERS"
  private val LAST_ITER_CONF_PATH = "matsim.modules.controler.lastIteration"

  private var baseConf: Config = _
  private var totalIterations: Int = _

  override def beforeAll(configMap: ConfigMap): Unit = {
    val conf = configMap.getWithDefault("config", "production/application-sfbay/base.conf")
    totalIterations = configMap.getWithDefault("iterations", 11)
    baseConf = BeamConfigUtils.parseFileSubstitutingInputDirectory(conf).resolve()
  }

  "SF Bay Run" should {

    "run beam 11 iterations and generate output for each " taggedAs (Periodic, ExcludeRegular) ignore {

      val config =
        baseConf.withValue(LAST_ITER_CONF_PATH, ConfigValueFactory.fromAnyRef(totalIterations - 1))

      config.getInt(LAST_ITER_CONF_PATH) should be(totalIterations - 1)

      val (_, output, _) = runBeamWithConfig(config)

      val outDir = Paths.get(output).toFile

      val itrDir = Paths.get(output, ITERS_DIR).toFile

      outDir should be a 'directory
      outDir.list should not be empty
      outDir.list should contain(ITERS_DIR)
      itrDir.list should have length totalIterations
      itrDir
        .listFiles()
        .foreach(directoryHasOnlyOneEventsFile)
    }
  }

  private def directoryHasOnlyOneEventsFile(itr: File): Assertion = {
    assertResult(1) {
      itr.list.count(fileName => fileName.endsWith(".events.csv") || fileName.endsWith(".events.csv.gz"))
    }
  }
}
