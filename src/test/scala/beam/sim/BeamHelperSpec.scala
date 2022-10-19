package beam.sim

import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigFactory
import org.scalatest.AppendedClues.convertToClueful
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

/**
  * @author Dmitry Openkov
  */
class BeamHelperSpec extends AnyWordSpecLike with Matchers {
  "updateConfigToCurrentVersion" when {
    "config doesn't contain a root RH config parameter" should {
      "not update the first rideHail manager with that parameter value" in {
        val cfg = ConfigFactory
          .parseString("beam.cfg.copyRideHailToFirstManager = true")
          .withFallback(testConfig("test/input/beamville/beam.conf"))
        val result = BeamHelper.updateConfigToCurrentVersion(cfg)
        val manager = result.getConfigList("beam.agentsim.agents.rideHail.managers").get(0)
        manager.getDouble("initialization.procedural.fractionOfInitialVehicleFleet") shouldBe 0.5 withClue
        "beam.conf doesn't contain beam.agentsim.agents.rideHail.initialization.procedural.fractionOfInitialVehicleFleet" +
        " because of that the value defined in beam.agentsim.agents.rideHail.managers shouldn't be overwritten"
      }
    }
    "config contains a root RH config parameter" should {
      "update the first rideHail manager with that parameter value" in {
        val cfg = ConfigFactory
          .parseString("beam.cfg.copyRideHailToFirstManager=true")
          .withFallback(testConfig("test/input/beamville/beam-urbansimv2_1person.conf"))
        val result = BeamHelper.updateConfigToCurrentVersion(cfg)
        val manager = result.getConfigList("beam.agentsim.agents.rideHail.managers").get(0)
        manager.getDouble("initialization.procedural.fractionOfInitialVehicleFleet") shouldBe 0.0001 withClue
        """beam-urbansimv2_1person.conf contains
          beam.agentsim.agents.rideHail.initialization.procedural.fractionOfInitialVehicleFleet = 0.0001
          and the value 'fractionOfInitialVehicleFleet' of the first manager should be overwritten"""
      }
    }
    "config contains a root RH config parameter but we don't say to copy it" should {
      "not update the first rideHail manager with that parameter value" in {
        val cfg = testConfig("test/input/beamville/beam-urbansimv2_1person.conf")
        val result = BeamHelper.updateConfigToCurrentVersion(cfg)
        val manager = result.getConfigList("beam.agentsim.agents.rideHail.managers").get(0)
        manager.getDouble("initialization.procedural.fractionOfInitialVehicleFleet") shouldBe 0.5 withClue
        """our config doesn't contain beam.cfg.copyRideHailToFirstManager=true
          because of that the value 'fractionOfInitialVehicleFleet' of the first manager should not be overwritten"""
      }
    }
    "config contains old rideHail configuration" should {
      "update the first rideHail manager with that parameter value" in {
        val cfg = testConfig("test/input/sf-light/sf-light-0.5k.conf")
        val result = BeamHelper.updateConfigToCurrentVersion(cfg)
        val manager = result.getConfigList("beam.agentsim.agents.rideHail.managers").get(0)
        manager.getDouble("defaultBaseCost") shouldBe 1.8 withClue
        """This config doesn't have beam.agentsim.agents.rideHail.managers defined
          and updateConfigToCurrentVersion should create it"""
      }
    }
  }
}
