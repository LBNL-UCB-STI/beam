package beam.periodic

import java.nio.file.Paths

import beam.sim.BeamHelper
import beam.tags.{ExcludeRegular, Periodic}
import beam.utils.BeamConfigUtils
import com.typesafe.config.{Config, ConfigValueFactory}
import org.scalatest.{BeforeAndAfterAllConfigMap, ConfigMap, Matchers, WordSpecLike}

class ApplicationSfbayRunSpec extends WordSpecLike with Matchers with BeforeAndAfterAllConfigMap with BeamHelper {

  private val ITERS_DIR = "ITERS"
  private val LAST_ITER_CONF_PATH = "matsim.modules.controler.lastIteration"

  private var baseConf: Config = _
  private var totalIterations: Int = _

  override def beforeAll(configMap: ConfigMap): Unit = {
    val conf = configMap.getWithDefault("config", "production/application-sfbay/base.conf")
    totalIterations = configMap.getWithDefault("iterations", 11)
    baseConf = BeamConfigUtils.parseFileSubstitutingInputDirectory(conf).resolve()
  }

  "SF Bay Run" must {

    "run beam 11 iterations and generate output for each " taggedAs (Periodic, ExcludeRegular) ignore {

      val config =
        baseConf.withValue(LAST_ITER_CONF_PATH, ConfigValueFactory.fromAnyRef(totalIterations - 1))

      config.getInt(LAST_ITER_CONF_PATH) should be(totalIterations - 1)

      val (_, output) = runBeamWithConfig(config)

      val outDir = Paths.get(output).toFile

      val itrDir = Paths.get(output, ITERS_DIR).toFile

      outDir should be a 'directory
      outDir.list should not be empty
      outDir.list should contain(ITERS_DIR)
      itrDir.list should have length totalIterations
      itrDir
        .listFiles()
        .foreach(
          itr => exactly(1, itr.list) should endWith(".events.csv").or(endWith(".events.csv.gz"))
        )
    }
  }
}
