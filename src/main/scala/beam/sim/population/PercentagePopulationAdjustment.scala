package beam.sim.population

import java.util.Random

import beam.sim.config.BeamConfig
import org.matsim.api.core.v01.population.Population

class PercentagePopulationAdjustment(beamConfig: BeamConfig) extends PopulationAdjustment {
  override def update(population: Population): Population = {

    removeMode(population,"car")

    assignModeUniformDistribution(population, "car",0.5);

    population
  }

  // remove mode from all attributes
  def removeMode(population: Population, modeToRemove: String): Unit = {
    population.getPersons.keySet().forEach { person =>
      val modes = population.getPersonAttributes.getAttribute(person.toString, "available-modes").toString
      population.getPersonAttributes
        .putAttribute(
          person.toString,
          "available-modes",
          modes.split(",").filterNot(_.equalsIgnoreCase(modeToRemove)).mkString(",")
        )
    }
  }

  def assignModeUniformDistribution(population: Population, mode: String, pct: Double): Unit = {
    val rand: Random = new Random(beamConfig.matsim.modules.global.randomSeed)
    val numPop = population.getPersons.size()
    rand.ints((numPop * pct).toLong, 1, numPop +1).forEach { num =>
      val modes = population.getPersonAttributes.getAttribute(num.toString, "available-modes").toString
      population.getPersonAttributes
        .putAttribute(
          num.toString,
          "available-modes",
          s"$modes,$mode"
        )
    }

  }
}
