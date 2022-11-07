package beam.integration.ridehail

import beam.integration.TestConstants
import beam.sim.config.MatSimBeamConfigBuilder
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.{Config, ConfigValueFactory}
import org.matsim.core.config.{Config => MatSimConfig}

import java.util

object RideHailTestHelper {

  def buildConfig(allocationManagerName: String): Config = {
    val beamConfig = testConfig("test/input/beamville/beam.conf")
      .resolve()
    val rhm: Config = beamConfig.getConfigList("beam.agentsim.agents.rideHail.managers").get(0)
    val updatedRhm = rhm
      .withValue("initialization.procedural.numDriversAsFractionOfPopulation", ConfigValueFactory.fromAnyRef(0.1))
      .withValue("allocationManager.requestBufferTimeoutInSeconds", ConfigValueFactory.fromAnyRef(0))
      .withValue("allocationManager.name", ConfigValueFactory.fromAnyRef(allocationManagerName))
      .root()
    val config = beamConfig
      .withValue("beam.outputs.events.fileOutputFormats", ConfigValueFactory.fromAnyRef("xml,csv"))
      .withValue(
        "beam.agentsim.agents.rideHail.managers",
        ConfigValueFactory.fromIterable(util.Arrays.asList(updatedRhm))
      )
      .withValue(
        TestConstants.KEY_AGENT_MODAL_BEHAVIORS_MODE_CHOICE_CLASS,
        ConfigValueFactory.fromAnyRef("ModeChoiceRideHailIfAvailable")
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
