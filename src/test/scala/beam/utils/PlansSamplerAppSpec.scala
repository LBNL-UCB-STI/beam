package beam.utils

import beam.sim.population.PopulationAdjustment
import beam.tags.{ExcludeRegular, Periodic}
import beam.utils.plan.sampling.PlansSampler
import org.matsim.core.config.ConfigUtils
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.matsim.utils.objectattributes.ObjectAttributes
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.matchers.should.Matchers

class PlansSamplerAppSpec extends AnyWordSpecLike with Matchers {

  val inputData: Array[String] = Array(
    "beam.sim.test/input/sf-light/sample/1k/population.xml.gz",
    "beam.sim.test/input/sf-light/shape/sflight_muni_mask.shp",
    "beam.sim.test/input/sf-light/r5/physsim-network.xml",
    "beam.sim.test/input/sf-light/ind_X_hh_out_test.csv.gz",
    "beam.sim.test/beam.sim.test-resources/vehicles.xml",
    "10",
    "output/beam.sim.test/plansampler/",
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
      config.plans().setInputFile("output/beam.sim.test/plansampler/population.xml.gz")
      config
        .plans()
        .setInputPersonAttributeFile("output/beam.sim.test/plansampler/populationAttributes.xml.gz")
      val dummyScenario: MutableScenario = ScenarioUtils.createMutableScenario(config)
      dummyScenario.setLocked()
      ScenarioUtils.loadScenario(dummyScenario)
      val attributes: ObjectAttributes = dummyScenario.getPopulation.getPersonAttributes

      attributes.getAttribute(
        attributes.toString.split(";")(0).stripPrefix("key="),
        PopulationAdjustment.EXCLUDED_MODES
      ) should equal(
        ""
      )
    }
  }
}
