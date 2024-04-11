package scripts

import beam.sim.population.PopulationAdjustment
import beam.tags.{ExcludeRegular, Periodic}
import beam.utils.FileUtils
import org.matsim.core.config.ConfigUtils
import org.matsim.core.population.PopulationUtils
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.annotation.nowarn

@nowarn
class PlansSamplerAppSpec extends AnyWordSpecLike with Matchers {

  val inputData: Array[String] = Array(
    "test/input/sf-light/sample/1k/population.xml.gz",
    "test/input/sf-light/shape/sflight_muni_mask.shp",
    "test/input/sf-light/r5/physsim-network.xml",
    "test/input/sf-light/ind_X_hh_out_test.csv.gz",
    "test/test-resources/vehicles.xml",
    "10",
    "output/test/plansampler/",
    "epsg:4326",
    "epsg:26910"
  )

  "PlanSamplerApp class" ignore {
    "assign available modes to agents " taggedAs (Periodic, ExcludeRegular) in {
      FileUtils.createDirectoryIfNotExists(inputData(6))
      val sampler = PlansSampler
      sampler.init(inputData)
      sampler.run()
      val config = ConfigUtils.createConfig
      config.plans().setInputFile("output/test/plansampler/population.xml.gz")

      config
        .plans()
        .setInputPersonAttributeFile("output/test/plansampler/populationAttributes.xml.gz")

      val dummyScenario: MutableScenario = ScenarioUtils.createMutableScenario(config)
      dummyScenario.setLocked()
      ScenarioUtils.loadScenario(dummyScenario)
      val person = dummyScenario.getPopulation.getPersons.values().iterator().next()

      PopulationUtils.getPersonAttribute(person, PopulationAdjustment.EXCLUDED_MODES) should equal(
        ""
      )
    }
  }
}
