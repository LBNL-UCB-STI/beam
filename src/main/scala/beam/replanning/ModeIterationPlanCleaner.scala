package beam.replanning

import beam.sim.config.BeamConfig
import com.google.inject.Inject
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Scenario
import org.matsim.api.core.v01.population._

import scala.collection.JavaConverters._

class ModeIterationPlanCleaner @Inject() (config: BeamConfig, scenario: Scenario) extends LazyLogging {

  private val clearModeIteration: Int = config.beam.replanning.clearModes.iteration

  private val clearModes: Set[String] =
    config.beam.replanning.clearModes.modes.getOrElse(List.empty).map(_.toLowerCase).toSet

  def clearModesAccordingToStrategy(iteration: Int): Unit = {
    if (
      clearModes.nonEmpty && (iteration == clearModeIteration
      || iteration > clearModeIteration
      && "AtBeginningAndAllSubsequentIterations".equalsIgnoreCase(config.beam.replanning.clearModes.strategy))
    ) {
      logger.debug("Cleaning modes {} for iteration {}", clearModes.mkString("(", ", ", ")"), iteration)
      replanModesForPopulation(scenario.getPopulation)
    }
  }

  private def replanModesForPopulation(population: Population): Unit = {
    var counter = 0
    population.getPersons.asScala.foreach { case (_, person) =>
      person.getSelectedPlan.getPlanElements.forEach {
        case leg: Leg if clearModes.contains(leg.getMode.toLowerCase) =>
          leg.setMode("")
          counter += 1
        case _ =>
      }
    }
    logger.debug("Cleaned modes for {} legs", counter)
  }
}
