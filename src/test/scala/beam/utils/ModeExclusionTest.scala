package beam.utils

import beam.sim.BeamHelper
import beam.sim.common.GeoUtilsImpl
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.utils.TestConfigUtils.testConfig
import beam.utils.csv.readers.BeamCsvScenarioReader
import beam.utils.plan.sampling.AvailableModeUtils
import beam.utils.scenario.{BeamScenarioLoader, ScenarioSource}
import org.matsim.core.scenario.ScenarioBuilder
import org.scalatest.{BeforeAndAfterEach, Matchers, WordSpecLike}
import org.scalatestplus.mockito.MockitoSugar

class ModeExclusionTest extends WordSpecLike with Matchers with MockitoSugar with BeforeAndAfterEach with BeamHelper {

  private val config = testConfig("test/input/beamville/beam.conf").resolve()
  private val beamConfigBase = BeamConfig(config)

  private val scenarioSource = mock[ScenarioSource]

  private val geoUtils = new GeoUtilsImpl(beamConfigBase)

  "Exclusion of mode" should {

    val beamScenario = loadScenario(beamConfigBase)
    val configBuilder = new MatSimBeamConfigBuilder(config)
    val matsimConfig = configBuilder.buildMatSimConf()
    val scenarioBuilder = ScenarioBuilder(matsimConfig, beamScenario.network)
    val persons = BeamCsvScenarioReader.readPersonsFile("test/test-resources/beam/input/population.csv")
    val plans = BeamCsvScenarioReader.readPlansFile("test/input/beamville/csvInput/plans.csv")
    val personIdsWithPlanTmp = plans.map(_.personId).toSet
    val personWithPlans = persons.filter(person => personIdsWithPlanTmp.contains(person.personId))
    val scenarioLoader = new BeamScenarioLoader(scenarioBuilder, beamScenario, scenarioSource, geoUtils)
    val population = scenarioLoader.buildPopulation(personWithPlans)

    "check for removal of single mode" in {
      AvailableModeUtils.getExcludedModesForPerson(population, "2") shouldBe Array("car")
    }

    "check for removal of multiple mode" in {
      AvailableModeUtils.getExcludedModesForPerson(population, "1") shouldBe Array("car", "walk")
    }

    "check for removal of empty mode" in {
      AvailableModeUtils.getExcludedModesForPerson(population, "3") shouldBe Array()
    }
  }
}
