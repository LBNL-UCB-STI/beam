package beam.sim.population

import java.util.Random

import beam.sim.config.BeamConfig
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.{Person, Population}

class PercentagePopulationAdjustment(beamConfig: BeamConfig) extends PopulationAdjustment with LazyLogging {
  override def update(population: Population): Population = {

    removeMode(population, "car")

    assignModeUniformDistribution(population, "car", 0.5)

    logModes(population)

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

  def existsMode(population: Population, personId: String, modeToCheck: String): Boolean = {
    val modes = population.getPersonAttributes.getAttribute(personId, "available-modes").toString
    modes.split(",").contains(modeToCheck)
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

  def logModes(population: Population): Unit = {
    import scala.collection.JavaConverters._
    logger.info("Modes' Availability:")
    population.getPersons.keySet().asScala.map(personId =>
      population.getPersonAttributes.getAttribute(personId.toString, "available-modes").toString.split(",")
    ).toList.flatten.groupBy(identity).mapValues(_.size).foreach(t => logger.info(t.toString()))
  }
}
