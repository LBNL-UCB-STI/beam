package beam.sim.population

import beam.integration.IntegrationSpecCommon
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.{BeamHelper, BeamScenario, BeamServices}
import beam.utils.FileUtils
import com.typesafe.config.ConfigValueFactory
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.scalatest.matchers.must.Matchers
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import org.scalatest.wordspec.AnyWordSpecLike

import java.nio.file.Files

class PopulationSamplingSpec extends AnyWordSpecLike with Matchers with BeamHelper with IntegrationSpecCommon {

  private def getObjectsForPopulationSampling(
    seed: Integer = 0
  ): (MutableScenario, BeamScenario, BeamConfig, BeamServices, String) = {
    if (seed.equals(0)) {
      return prepareObjectsForPopulationSampling(getConfigWithRandomSeed)
    }
    prepareObjectsForPopulationSampling(getConfigWithSeed(seed))
  }

  private def getConfigWithRandomSeed: BeamConfig = {
    BeamConfig(
      baseConfig
        .withValue(
          "beam.agentsim.agentSampleSizeAsFractionOfPopulation",
          ConfigValueFactory.fromAnyRef(0.5)
        )
        .resolve()
    )
  }

  private def getConfigWithSeed(seed: Integer): BeamConfig = {
    BeamConfig(
      baseConfig
        .withValue(
          "beam.agentsim.agentSampleSizeAsFractionOfPopulation",
          ConfigValueFactory.fromAnyRef(0.5)
        )
        .withValue("beam.agentsim.randomSeed", ConfigValueFactory.fromAnyRef(seed))
        .resolve()
    )
  }

  private def prepareObjectsForPopulationSampling(
    config: BeamConfig
  ): (MutableScenario, BeamScenario, BeamConfig, BeamServices, String) = {
    val beamScenario = loadScenario(config)
    val configBuilder = new MatSimBeamConfigBuilder(baseConfig)
    val matsimConfig = configBuilder.buildMatSimConf()
    matsimConfig.planCalcScore().setMemorizingExperiencedPlans(true)
    FileUtils.setConfigOutputFile(config, matsimConfig)

    val scenario = ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]
    scenario.setNetwork(beamScenario.network)

    val injector = org.matsim.core.controler.Injector.createInjector(
      scenario.getConfig,
      module(baseConfig, config, scenario, beamScenario)
    )

    val beamServices: BeamServices = injector.getInstance(classOf[BeamServices])
    val temporaryOutputDirectory = Files.createTempDirectory("dummyOutputDirectory").toString
    (scenario, beamScenario, config, beamServices, temporaryOutputDirectory)
  }

  "PopulationSampling" must {
    "sample the same agents for the same randomSeed value" in {
      val samplingDataOne = getObjectsForPopulationSampling(1441)
      val samplingDataTwo = getObjectsForPopulationSampling(1441)
      val agentsBeforeSamplingOneSize = samplingDataOne._1.getPopulation.getPersons.keySet().size()
      PopulationScaling.samplePopulation(
        samplingDataOne._1,
        samplingDataOne._2,
        samplingDataOne._3,
        samplingDataOne._4,
        samplingDataOne._5
      )

      agentsBeforeSamplingOneSize should be <= 50
      val agentsAfterSamplingOne =
        samplingDataOne._1.getPopulation.getPersons.keySet().stream().map(a => a.leftSide).toArray

      PopulationScaling.samplePopulation(
        samplingDataTwo._1,
        samplingDataTwo._2,
        samplingDataTwo._3,
        samplingDataTwo._4,
        samplingDataTwo._5
      )
      val agentsAfterSamplingTwo =
        samplingDataTwo._1.getPopulation.getPersons.keySet().stream().map(a => a.leftSide).toArray

      agentsAfterSamplingOne shouldBe agentsAfterSamplingTwo
    }

    "sample different agents if randomSeed is not set" in {
      val samplingDataOne = getObjectsForPopulationSampling()
      val samplingDataTwo = getObjectsForPopulationSampling()
      val agentsBeforeSamplingOneSize = samplingDataOne._1.getPopulation.getPersons.keySet().size()
      PopulationScaling.samplePopulation(
        samplingDataOne._1,
        samplingDataOne._2,
        samplingDataOne._3,
        samplingDataOne._4,
        samplingDataOne._5
      )
      PopulationScaling.samplePopulation(
        samplingDataTwo._1,
        samplingDataTwo._2,
        samplingDataTwo._3,
        samplingDataTwo._4,
        samplingDataTwo._5
      )

      agentsBeforeSamplingOneSize should be <= 50
      val agentsAfterSamplingOne =
        samplingDataOne._1.getPopulation.getPersons.keySet().stream().map(a => a.leftSide).toArray
      val agentsAfterSamplingTwo =
        samplingDataTwo._1.getPopulation.getPersons.keySet().stream().map(a => a.leftSide).toArray

      agentsAfterSamplingOne should not equal agentsAfterSamplingTwo
    }

    "sample different agents for different randomSeeds" in {
      val samplingDataOne = getObjectsForPopulationSampling(1441)
      val samplingDataTwo = getObjectsForPopulationSampling(23)
      val agentsBeforeSamplingOneSize = samplingDataOne._1.getPopulation.getPersons.keySet().size()
      PopulationScaling.samplePopulation(
        samplingDataOne._1,
        samplingDataOne._2,
        samplingDataOne._3,
        samplingDataOne._4,
        samplingDataOne._5
      )
      PopulationScaling.samplePopulation(
        samplingDataTwo._1,
        samplingDataTwo._2,
        samplingDataTwo._3,
        samplingDataTwo._4,
        samplingDataTwo._5
      )

      agentsBeforeSamplingOneSize should be <= 50
      val agentsAfterSamplingOne =
        samplingDataOne._1.getPopulation.getPersons.keySet().stream().map(a => a.leftSide).toArray
      val agentsAfterSamplingTwo =
        samplingDataTwo._1.getPopulation.getPersons.keySet().stream().map(a => a.leftSide).toArray

      agentsAfterSamplingOne should not equal agentsAfterSamplingTwo
    }
  }
}
