package beam.sim.population

import beam.sim.{BeamScenario, BeamServices}
import org.matsim.api.core.v01.Scenario
import org.matsim.api.core.v01.population.Population

case class DefaultPopulationAdjustment(beamServices: BeamServices) extends PopulationAdjustment {

  override lazy val scenario: Scenario = beamServices.matsimServices.getScenario
  override lazy val beamScenario: BeamScenario = beamServices.beamScenario

  override def updatePopulation(scenario: Scenario): Population = {
    scenario.getPopulation
  }
}
