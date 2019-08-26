package beam.sim.population

import java.util.Random

import beam.sim.{BeamScenario, BeamServices}
import org.matsim.api.core.v01.population.{Person, Population}
import org.matsim.api.core.v01.{Id, Scenario}

case class ExcludeHalfTransit(beamServices: BeamServices) extends PopulationAdjustment {

  override lazy val scenario: Scenario = beamServices.matsimServices.getScenario
  override lazy val beamScenario: BeamScenario = beamServices.beamScenario

  override def updatePopulation(scenario: Scenario): Population = {
    val population = scenario.getPopulation

    removeModeAll(population, "walk_transit")
    removeModeAll(population, "drive_transit")
    removeModeAll(population, "bike_transit")
    removeModeAll(population, "ride_hail_transit")

    assignModeUniformDistribution(population: Population, "walk_transit", 0.5)
    assignModeUniformDistribution(population: Population, "drive_transit", 0.5)
    assignModeUniformDistribution(population: Population, "bike_transit", 0.5)
    assignModeUniformDistribution(population: Population, "ride_hail_transit", 0.5)

    population
  }

}
