package beam.sim.population

import beam.sim.config.BeamConfig
import org.matsim.api.core.v01.population.Population

class DefaultPopulationAdjustment(beamConfig: BeamConfig) extends PopulationAdjustment {
  override def update(population: Population): Population = {
    population
  }
}
