package beam.sim.population

import beam.sim.BeamServices
import beam.sim.population.PopulationAdjustment.AVAILABLE_MODES
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Scenario
import org.matsim.api.core.v01.population.Population

trait PopulationAdjustment extends LazyLogging {
  final def update(scenario: Scenario): Population = {
    val result = updatePopulation(scenario)

    logModes(result)

    result
  }


  protected final def logModes(population: Population): Unit = {
    import scala.collection.JavaConverters._
    logger.info("Modes' Availability:")
    population.getPersons.keySet().asScala.map(personId =>
      population.getPersonAttributes.getAttribute(personId.toString, AVAILABLE_MODES).toString.split(",")
    ).toList.flatten.groupBy(identity).mapValues(_.size).foreach(t => logger.info(t.toString()))
  }

  protected def updatePopulation(scenario: Scenario): Population

  protected def addMode(population: Population, personId: String, mode: String): Unit = {
    val modes = population.getPersonAttributes.getAttribute(personId, AVAILABLE_MODES).toString
    if (!existsMode(population, personId, mode)) {
      population.getPersonAttributes
        .putAttribute(
          personId,
          AVAILABLE_MODES,
          s"$modes,$mode"
        )
    }
  }

  protected def existsMode(population: Population, personId: String, modeToCheck: String): Boolean = {
    val modes = population.getPersonAttributes.getAttribute(personId, AVAILABLE_MODES).toString
    modes.split(",").contains(modeToCheck)
  }

  protected def removeMode(population: Population, personId: String, modeToRemove: String*): Unit = {

    val modes = population.getPersonAttributes.getAttribute(personId, AVAILABLE_MODES).toString
    modeToRemove.foreach(mode =>
      population.getPersonAttributes
        .putAttribute(
          personId,
          AVAILABLE_MODES,
          modes.split(",").filterNot(_.equalsIgnoreCase(mode)).mkString(",")
        )
    )
  }

  // remove mode from all attributes
  protected def removeModeAll(population: Population, modeToRemove: String*): Unit = {
    population.getPersons.keySet().forEach { person =>
      val modes = population.getPersonAttributes.getAttribute(person.toString, AVAILABLE_MODES).toString
      modeToRemove.foreach(mode =>
        population.getPersonAttributes
          .putAttribute(
            person.toString,
            AVAILABLE_MODES,
            modes.split(",").filterNot(_.equalsIgnoreCase(mode)).mkString(",")
          )
      )
    }
  }
}

object PopulationAdjustment {
  val DEFAULT_ADJUSTMENT = "DEFAULT_ADJUSTMENT"
  val PERCENTAGE_ADJUSTMENT = "PERCENTAGE_ADJUSTMENT"
  val DIFFUSION_POTENTIAL_ADJUSTMENT = "DIFFUSION_POTENTIAL_ADJUSTMENT"
  val AVAILABLE_MODES = "available-modes"

  def getPopulationAdjustment(beamServices: BeamServices): PopulationAdjustment = {
    beamServices.beamConfig.beam.agentsim.populationAdjustment match {
      case DEFAULT_ADJUSTMENT =>
        new DefaultPopulationAdjustment(beamServices)
      case PERCENTAGE_ADJUSTMENT =>
        new PercentagePopulationAdjustment(beamServices)
      case DIFFUSION_POTENTIAL_ADJUSTMENT =>
        new DiffusionPotentialPopulationAdjustment(beamServices)
      case adjClass =>
        try {
          Class
            .forName(adjClass)
            .getDeclaredConstructors()(0)
            .newInstance(beamServices)
            .asInstanceOf[PopulationAdjustment]
        } catch {
          case e: Exception =>
            throw new IllegalStateException(s"Unknown PopulationAdjustment: $adjClass", e)
        }
    }
  }
}
