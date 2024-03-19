package beam.utils

import beam.sim.BeamHelper
import beam.sim.common.GeoUtilsImpl
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.utils.TestConfigUtils.testConfig
import beam.utils.csv.readers.BeamCsvScenarioReader
import beam.utils.plan.sampling.AvailableModeUtils
import beam.utils.scenario.{BeamScenarioLoader, ScenarioSource}
import org.matsim.api.core.v01.Id
import org.matsim.core.scenario.ScenarioBuilder
import org.mockito.Mockito.mock
import org.scalatest.BeforeAndAfterEach
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.matchers.should.Matchers

class ModeExclusionTest extends AnyWordSpecLike with Matchers with BeforeAndAfterEach with BeamHelper {

  private val config = testConfig("test/input/beamville/beam.conf").resolve()
  private val beamConfigBase = BeamConfig(config)

  private val scenarioSource = mock(classOf[ScenarioSource])

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
    val person1 = population.getPersons.get(Id.createPersonId("1"))
    val person2 = population.getPersons.get(Id.createPersonId("2"))
    val person3 = population.getPersons.get(Id.createPersonId("3"))

    "check for removal of single mode" in {
      AvailableModeUtils.getExcludedModesForPerson(person2) shouldBe Array("car")
    }

    "check for removal of multiple mode" in {
      AvailableModeUtils.getExcludedModesForPerson(person1) shouldBe Array("car", "walk")
    }

    "check for removal of empty mode" in {
      AvailableModeUtils.getExcludedModesForPerson(person3) shouldBe Array()
    }
  }
}
