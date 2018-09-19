package beam.sim.population

import beam.sim.config.BeamConfig
import org.matsim.api.core.v01.population.Population

class DefaultPopulationAdjustment(beamConfig: BeamConfig) extends PopulationAdjustment {
  override def updatePopulation(population: Population): Population = {
    population
  }
}
