package beam.utils

import com.typesafe.config.{Config, ConfigFactory, ConfigValueFactory}

object TestConfigUtils {
  val testOutputDir = "output/test/"

  val configFileName = "test/input/beamville/beam.conf"
  val configLocation: Config = ConfigFactory.parseString("config=" + configFileName)

  val minimumValidBeamConfig: Config = {
    ConfigFactory.parseString(
      """|beam.agentsim.agents.vehicles.sharedFleets=[]
         |beam.debug.stuckAgentDetection.thresholds=[]
         |matsim.modules.strategy.parameterset=[]
         |matsim.modules.planCalcScore.parameterset=[]
         |""".stripMargin
    )
  }

  def testConfig(conf: String): Config =
    BeamConfigUtils
      .parseFileSubstitutingInputDirectory(conf)
      .withValue("beam.outputs.baseOutputDirectory", ConfigValueFactory.fromAnyRef(testOutputDir))
      .withFallback(configLocation)
}
