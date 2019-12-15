package beam.sim.population

import beam.sim.{BeamScenario, BeamServices}
import org.matsim.api.core.v01.population.Population
import org.matsim.api.core.v01.Scenario

case class PercentagePopulationAdjustment(beamServices: BeamServices) extends PopulationAdjustment {

  override lazy val scenario: Scenario = beamServices.matsimServices.getScenario
  override lazy val beamScenario: BeamScenario = beamServices.beamScenario

  override def updatePopulation(scenario: Scenario): Population = {
    val population = scenario.getPopulation

    removeModeAll(population, "car")

    assignModeUniformDistribution(population, "car", 0.5)

    population
  }

}
