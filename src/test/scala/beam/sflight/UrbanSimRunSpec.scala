package beam.sflight

import java.nio.file.Paths

import beam.sim.BeamHelper
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigValueFactory
import org.scalatest.{BeforeAndAfterAllConfigMap, ConfigMap, Matchers, WordSpecLike}

/**
  * Created by colinsheppard
  */

class UrbanSimRunSpec extends WordSpecLike with Matchers with BeamHelper with BeforeAndAfterAllConfigMap {

  private val ITERS_DIR = "ITERS"
  private val LAST_ITER_CONF_PATH = "matsim.modules.controler.lastIteration"
  private val METRICS_LEVEL = "beam.metrics.level"
  private val KAMON_INFLUXDB = "kamon.modules.kamon-influxdb.auto-start"

  private var configMap: ConfigMap = _

  override def beforeAll(configMap: ConfigMap): Unit = {
    this.configMap = configMap
  }

  "UrbanSimRun" must {
    "run  urbansim-1k.conf" in {
      val confPath = configMap.getWithDefault("config", "test/input/sf-light/urbansim-1k.conf")
      val totalIterations = configMap.getWithDefault("iterations", "1").toInt
      val baseConf = testConfig(confPath)
        .resolve()
        .withValue(LAST_ITER_CONF_PATH, ConfigValueFactory.fromAnyRef(totalIterations - 1))
      baseConf.getInt(LAST_ITER_CONF_PATH) should be(totalIterations - 1)
      val conf = baseConf
        .withValue(METRICS_LEVEL, ConfigValueFactory.fromAnyRef("off"))
        .withValue(KAMON_INFLUXDB, ConfigValueFactory.fromAnyRef("no"))
        .resolve()
      val (_, output) = runBeamWithConfig(conf)

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
