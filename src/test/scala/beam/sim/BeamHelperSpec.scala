package beam.sim

import beam.utils.TestConfigUtils.testConfig
import org.scalatest.AppendedClues.convertToClueful
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

/**
  * @author Dmitry Openkov
  */
class BeamHelperSpec extends AnyWordSpecLike with Matchers {
  "updateConfigToCurrentVersion" when {
    "rideHail cfg doesn't contain a value" should {
      "not update the first rideHail manager" in {
        val cfg = testConfig("test/input/beamville/beam.conf")
        val result = BeamHelper.updateConfigToCurrentVersion(cfg)
        val manager = result.getConfigList("beam.agentsim.agents.rideHail.managers").get(0)
        manager.getDouble("initialization.procedural.fractionOfInitialVehicleFleet") shouldBe 0.5 withClue
        "beam.conf doesn't contain beam.agentsim.agents.rideHail.initialization.procedural.fractionOfInitialVehicleFleet" +
        " because of that the value defined in beam.agentsim.agents.rideHail.managers shouldn't be overwritten"
      }
    }
    "rideHail cfg contain a value" should {
      "update the first rideHail manager with that value" in {
        val cfg = testConfig("test/input/beamville/beam-urbansimv2_1person.conf")
        val result = BeamHelper.updateConfigToCurrentVersion(cfg)
        val manager = result.getConfigList("beam.agentsim.agents.rideHail.managers").get(0)
        manager.getDouble("initialization.procedural.fractionOfInitialVehicleFleet") shouldBe 0.0001 withClue
        """beam-urbansimv2_1person.conf contains
          beam.agentsim.agents.rideHail.initialization.procedural.fractionOfInitialVehicleFleet = 0.0001
          and the value 'fractionOfInitialVehicleFleet' of the first manager should be overwritten"""
      }
    }
  }
}
