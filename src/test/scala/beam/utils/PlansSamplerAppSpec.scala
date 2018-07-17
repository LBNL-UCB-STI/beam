package beam.utils

import beam.utils.scripts.PlansSampler
import org.matsim.core.config.ConfigUtils
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.matsim.utils.objectattributes.ObjectAttributes
import org.scalatest.{Matchers, WordSpecLike}

class PlansSamplerAppSpec extends WordSpecLike with Matchers {
  val inputData: Array[String] = Array("test/input/beamville/population.xml",
    "test/input/beamville/shape/beamville_aoi.shp",
    "test/input/beamville/physsim-network.xml",
    "test/input/beamville/hhOut.csv",
    "test/input/beamville/vehicles.xml", "3",
    "/Users/sfeygin/current_code/java/research/beam/output/test/plansampler",
    "epsg:4326",
    "epsg:32631")

  "PlanSamplerApp class" should {
    "assign available modes to agents " in {
      val sampler = PlansSampler
      sampler.init(inputData)
      sampler.run()
      val config = ConfigUtils.createConfig
      config.plans().setInputFile("output/test/plansampler/population.xml.gz")
      config.plans().setInputPersonAttributeFile("output/test/plansampler/populationAttributes.xml.gz")
      val dummyScenario: MutableScenario = ScenarioUtils.createMutableScenario(config)
      dummyScenario.setLocked()
      ScenarioUtils.loadScenario(dummyScenario)
      val attributes: ObjectAttributes = dummyScenario.getPopulation.getPersonAttributes
      attributes.getAttribute("1-0", "available_modes") should equal("car,ride_hail,bike,bus,funicular,gondola,cable_car,ferry,tram,transit,rail,subway,tram")
    }
  }
}
