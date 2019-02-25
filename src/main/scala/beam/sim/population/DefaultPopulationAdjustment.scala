package beam.sim.population

import beam.sim.BeamServices
import org.matsim.api.core.v01.Scenario
import org.matsim.api.core.v01.population.Population

case class DefaultPopulationAdjustment(beamServices: BeamServices)
    extends PopulationAdjustment {
  override def updatePopulation(scenario: Scenario): Population = {
    scenario.getPopulation
  }
}
