package beam.sim.population

import beam.sim.config.BeamConfig
import org.matsim.api.core.v01.population.Population
import org.matsim.utils.objectattributes.ObjectAttributes

trait PopulationAdjustment {
  def update(population: Population): Population
}
