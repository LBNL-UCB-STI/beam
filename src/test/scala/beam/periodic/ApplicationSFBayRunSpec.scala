package beam.periodic

import java.nio.file.Paths

import beam.sim.BeamHelper
import beam.tags.Periodic
import beam.utils.BeamConfigUtils
import com.typesafe.config.ConfigValueFactory
import org.scalatest.{Matchers, WordSpecLike}

class ApplicationSfbayRunSpec extends WordSpecLike with Matchers with BeamHelper {

  "SF Bay Run" must {
    val baseConf = BeamConfigUtils.parseFileSubstitutingInputDirectory("production/application-sfbay/base.conf").resolve()

    "run beam 11 iterations and generate output for each " taggedAs Periodic in {
      val NUM_ITERATIONS = 11

      val config = baseConf.withValue("matsim.modules.controler.lastIteration", ConfigValueFactory.fromAnyRef(NUM_ITERATIONS))

      config.getInt("matsim.modules.controler.lastIteration") should be (NUM_ITERATIONS)

      val (_, output) = runBeamWithConfig(config)


      val outDir = Paths.get(output).toFile
      val itrDir = Paths.get(output, "ITERS").toFile

      outDir should be a 'directory
      outDir.list should not be empty
      outDir.list should contain ("ITERS")
      itrDir.list should have length NUM_ITERATIONS+1
      itrDir.listFiles().foreach(itr => exactly(1, itr.list) should endWith (".events.csv"))
    }
  }
}
