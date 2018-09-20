package beam.sim.population

import java.util.Random

import beam.sim.config.BeamConfig
import org.matsim.api.core.v01.{Id, Scenario}
import org.matsim.api.core.v01.population.{Person, Population}

class PercentagePopulationAdjustment(beamConfig: BeamConfig) extends PopulationAdjustment {

  override def updatePopulation(scenario: Scenario): Population = {
    val population = scenario.getPopulation

    removeModeAll(population, "car")

    assignModeUniformDistribution(population, "car", 0.5)

    population
  }

  def assignModeUniformDistribution(population: Population, mode: String, pct: Double): Unit = {
    val rand: Random = new Random(beamConfig.matsim.modules.global.randomSeed)
    val numPop = population.getPersons.size()
    rand.ints(0, numPop).distinct().limit((numPop * pct).toLong).forEach { num =>
      val personId = population.getPersons.keySet().toArray(new Array[Id[Person]](0))(num).toString
      addMode(population, personId, mode)
    }
  }
}
