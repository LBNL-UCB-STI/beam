package beam.sim.population

import beam.router.Modes.BeamMode
import beam.sim.{BeamScenario, BeamServices}
import org.matsim.api.core.v01.population.Population
import org.matsim.api.core.v01.Scenario

case class PercentagePopulationAdjustment(beamServices: BeamServices) extends PopulationAdjustment {

  override lazy val scenario: Scenario = beamServices.matsimServices.getScenario
  override lazy val beamScenario: BeamScenario = beamServices.beamScenario

  override def updatePopulation(scenario: Scenario): Population = {
    val population = scenario.getPopulation

    Seq(BeamMode.CAR, BeamMode.CAR_HOV2, BeamMode.CAR_HOV3).foreach { mode =>
      removeModeAll(population, mode.value)
      assignModeUniformDistribution(population, mode.value, 0.5)
    }

    population
  }

}
