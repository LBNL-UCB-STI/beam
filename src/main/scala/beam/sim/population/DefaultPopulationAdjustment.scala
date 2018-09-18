package beam.sim.population
import org.matsim.api.core.v01.population.Population
import org.matsim.utils.objectattributes.ObjectAttributes

class DefaultPopulationAdjustment extends PopulationAdjustment  {
  override def update(population: Population, personAttributes: ObjectAttributes): (Population, ObjectAttributes) = {
    (population, personAttributes)
  }
}
