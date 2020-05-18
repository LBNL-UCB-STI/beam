package beam.replanning
import beam.sim.config.BeamConfig
import com.google.inject.Inject
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Scenario
import org.matsim.api.core.v01.population._
import org.matsim.core.controler.events.IterationStartsEvent
import org.matsim.core.controler.listener.IterationStartsListener

import scala.collection.JavaConverters._

class ModeIterationPlanCleaner @Inject()(config: BeamConfig, scenario: Scenario)
    extends IterationStartsListener
    with LazyLogging {

  private val clearModeIteration: Int = config.beam.replanning.clearModesAtStartOfIteration.atIteration
  private val clearModes: Set[String] =
    config.beam.replanning.clearModesAtStartOfIteration.modes.toSet

  override def notifyIterationStarts(event: IterationStartsEvent): Unit = {
    if (event.getIteration == clearModeIteration) {
      logger.debug("Cleaning modes for iteration {}", event.getIteration)
      replanModesForPopulation(scenario.getPopulation)
    }
  }

  private def replanModesForPopulation(population: Population): Unit = {
    population.getPersons.asScala.foreach {
      case (_, person) =>
        person.getSelectedPlan.getPlanElements.forEach {
          case leg: Leg if clearModes.contains(leg.getMode.toLowerCase) => leg.setMode("")
          case _                                                        =>
        }
    }
  }
}
