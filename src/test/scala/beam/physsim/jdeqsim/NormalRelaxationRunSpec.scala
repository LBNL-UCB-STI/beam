package beam.physsim.jdeqsim

import beam.sim.BeamHelper
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAllConfigMap
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class NormalRelaxationRunSpec extends AnyWordSpecLike with Matchers with BeamHelper with BeforeAndAfterAllConfigMap {

  "Normal Relaxation" must {
    "run sf-light scenario for two iteration" in {

      val baseConf = ConfigFactory
        .parseString(s"""
                       |beam.agentsim.lastIteration = 2
                       |beam.physsim.relaxation.type = "normal"
                     """.stripMargin)
        .withFallback(testConfig("test/input/beamville/beam.conf"))
        .resolve()

      runBeamWithConfig(baseConf)
    }
  }
}
