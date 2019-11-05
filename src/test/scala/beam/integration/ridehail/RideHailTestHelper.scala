package beam.integration.ridehail

import beam.integration.TestConstants
import beam.sim.config.MatSimBeamConfigBuilder
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.{Config, ConfigValueFactory}
import org.matsim.core.config.{Config => MatSimConfig}

object RideHailTestHelper {

  def buildConfig(allocationManagerName: String): Config = {
    val config = testConfig("test/input/beamville/beam.conf")
      .resolve()
      .withValue("beam.outputs.events.fileOutputFormats", ConfigValueFactory.fromAnyRef("xml,csv"))
      .withValue(
        "beam.agentsim.agents.rideHail.allocationManager.name",
        ConfigValueFactory.fromAnyRef(allocationManagerName)
      )
      .withValue(
        TestConstants.KEY_AGENT_MODAL_BEHAVIORS_MODE_CHOICE_CLASS,
        ConfigValueFactory.fromAnyRef("ModeChoiceRideHailIfAvailable")
      )
      .withValue(
        "beam.agentsim.agents.rideHail.initialization.procedural.numDriversAsFractionOfPopulation",
        ConfigValueFactory.fromAnyRef(0.1)
      )
      .withValue(
        "beam.agentsim.agents.rideHail.allocationManager.requestBufferTimeoutInSeconds",
        ConfigValueFactory.fromAnyRef(0)
      )
      .withValue("beam.debug.stuckAgentDetection.enabled", ConfigValueFactory.fromAnyRef(true))
      .withValue("matsim.modules.controler.lastIteration", ConfigValueFactory.fromAnyRef(0))
      .withValue("beam.agentsim.lastIteration", ConfigValueFactory.fromAnyRef(0))
      .resolve()

    config
  }

  def buildMatsimConfig(config: Config): MatSimConfig = {
    val configBuilder = new MatSimBeamConfigBuilder(config)

    val matsimConfig = configBuilder.buildMatSimConf()
    matsimConfig.planCalcScore().setMemorizingExperiencedPlans(true)
    matsimConfig
  }

}
