package beam.sim.population

import java.util.Random

import beam.sim.config.BeamConfig
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.{Person, Population}

class PercentagePopulationAdjustment(beamConfig: BeamConfig) extends PopulationAdjustment {

  override def updatePopulation(population: Population): Population = {

    removeModeAll(population, "car")

    assignModeUniformDistribution(population, "car", 0.5)

    population
  }

  def assignModeUniformDistribution(population: Population, mode: String, pct: Double): Unit = {
    val rand: Random = new Random(beamConfig.matsim.modules.global.randomSeed)
    val numPop = population.getPersons.size()
    rand.ints(0, numPop).distinct().limit((numPop * pct).toLong).forEach { num =>
      val personId = population.getPersons.keySet().toArray(new Array[Id[Person]](0))(num).toString
      val modes = population.getPersonAttributes.getAttribute(personId, "available-modes").toString
      if (!existsMode(population, personId, mode)) {
        population.getPersonAttributes
          .putAttribute(
            personId,
            "available-modes",
            s"$modes,$mode"
          )
      }
    }
  }
}
