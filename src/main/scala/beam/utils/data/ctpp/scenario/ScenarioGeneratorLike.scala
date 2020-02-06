package beam.utils.data.ctpp.scenario

import org.matsim.api.core.v01.population.Population
import org.matsim.households.Households

import scala.concurrent.Future

trait ScenarioGeneratorLike {
  def generate: Future[(Households, Population)]
}
