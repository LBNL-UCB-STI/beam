package beam.periodic

import java.nio.file.Paths

import beam.sim.BeamHelper
import beam.tags.Periodic
import beam.utils.BeamConfigUtils
import com.typesafe.config.{Config, ConfigValueFactory}
import org.scalatest.{BeforeAndAfterAllConfigMap, ConfigMap, Matchers, WordSpecLike}

class ApplicationSfbayRunSpec extends WordSpecLike with Matchers with BeforeAndAfterAllConfigMap with BeamHelper {

  var baseConf: Config = _
  var totalIterations: Int = _

  override def beforeAll(configMap: ConfigMap) = {
    val conf = configMap.getWithDefault("config", "production/application-sfbay/base.conf")
    totalIterations = configMap.getWithDefault("iterations", 11)
    baseConf = BeamConfigUtils.parseFileSubstitutingInputDirectory(conf).resolve()
  }

  "SF Bay Run" must {

    "run beam 11 iterations and generate output for each " taggedAs Periodic in {


      val config = baseConf.withValue("matsim.modules.controler.lastIteration", ConfigValueFactory.fromAnyRef(totalIterations-1))

      config.getInt("matsim.modules.controler.lastIteration") should be (totalIterations-1)

      val (_, output) = runBeamWithConfig(config)


      val outDir = Paths.get(output).toFile
      val itrDir = Paths.get(output, "ITERS").toFile

      outDir should be a 'directory
      outDir.list should not be empty
      outDir.list should contain ("ITERS")
      itrDir.list should have length totalIterations
      itrDir.listFiles().foreach(itr => exactly(1, itr.list) should endWith (".events.csv"))
    }
  }
}
