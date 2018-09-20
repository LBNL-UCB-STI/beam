package beam.sim.population

import beam.sim.config.BeamConfig
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Scenario
import org.matsim.api.core.v01.population.Population

trait PopulationAdjustment extends LazyLogging {
  final def update(scenario: Scenario): Population = {
    val result = updatePopulation(scenario)

    logModes(result)

    result
  }

  protected def updatePopulation(scenario: Scenario): Population

  protected def existsMode(population: Population, personId: String, modeToCheck: String): Boolean = {
    val modes = population.getPersonAttributes.getAttribute(personId, "available-modes").toString
    modes.split(",").contains(modeToCheck)
  }

  protected def addMode(population: Population, personId: String, mode: String): Unit = {
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

  protected def removeMode(population: Population, personId: String, modeToRemove: String*): Unit = {

    val modes = population.getPersonAttributes.getAttribute(personId, "available-modes").toString
    modeToRemove.foreach(mode =>
      population.getPersonAttributes
        .putAttribute(
          personId,
          "available-modes",
          modes.split(",").filterNot(_.equalsIgnoreCase(mode)).mkString(",")
        )
    )
  }

  // remove mode from all attributes
  protected def removeModeAll(population: Population, modeToRemove: String*): Unit = {
    population.getPersons.keySet().forEach { person =>
      val modes = population.getPersonAttributes.getAttribute(person.toString, "available-modes").toString
      modeToRemove.foreach(mode =>
        population.getPersonAttributes
          .putAttribute(
            person.toString,
            "available-modes",
            modes.split(",").filterNot(_.equalsIgnoreCase(mode)).mkString(",")
          )
      )
    }
  }

  protected final def logModes(population: Population): Unit = {
    import scala.collection.JavaConverters._
    logger.info("Modes' Availability:")
    population.getPersons.keySet().asScala.map(personId =>
      population.getPersonAttributes.getAttribute(personId.toString, "available-modes").toString.split(",")
    ).toList.flatten.groupBy(identity).mapValues(_.size).foreach(t => logger.info(t.toString()))
  }
}

object PopulationAdjustment {
  val DEFAULT_ADJUSTMENT = "DEFAULT_ADJUSTMENT"
  val PERCENTAGE_ADJUSTMENT = "PERCENTAGE_ADJUSTMENT"
  val DIFFUSION_POTENTIAL_ADJUSTMENT = "DIFFUSION_POTENTIAL_ADJUSTMENT"

  def getPopulationAdjustment(adjKey: String, beamConfig: BeamConfig): PopulationAdjustment = {
    adjKey match {
      case DEFAULT_ADJUSTMENT =>
        new DefaultPopulationAdjustment(beamConfig)
      case PERCENTAGE_ADJUSTMENT =>
        new PercentagePopulationAdjustment(beamConfig)
      case DIFFUSION_POTENTIAL_ADJUSTMENT =>
        new DiffusionPotentialPopulationAdjustment(beamConfig)
      case adjClass =>
        try {
          Class
            .forName(adjClass)
            .getDeclaredConstructors()(0)
            .newInstance(beamConfig)
            .asInstanceOf[PopulationAdjustment]
        } catch {
          case e: Exception =>
            throw new IllegalStateException(s"Unknown PopulationAdjustment: $adjClass", e)
        }
    }
  }
}
