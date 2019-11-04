package beam.replanning
import beam.sim.config.BeamConfig
import com.google.inject.Inject
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Scenario
import org.matsim.api.core.v01.population._
import org.matsim.core.controler.events.IterationStartsEvent
import org.matsim.core.controler.listener.IterationStartsListener
import shapeless._

import scala.collection.JavaConverters._

class NonCarModeIterationPlanCleaner @Inject()(config: BeamConfig, scenario: Scenario)
    extends IterationStartsListener
    with LazyLogging {

  private val clearModeIterationLens
    : Lens[BeamConfig, Int] = lens[BeamConfig] >> 'beam >> 'replanning >> 'cleanNonCarModesInIteration

  override def notifyIterationStarts(event: IterationStartsEvent): Unit = {
    if (event.getIteration == clearModeIterationLens.get(config)) {
      logger.debug("Cleaning non car modes for iteration {}", event.getIteration)
      replanNonCarModesPopulation(scenario.getPopulation)
    }
  }

  private def replanNonCarModesPopulation(population: Population): Unit = {
    population.getPersons.asScala.foreach {
      case (_, person) =>
        person.getSelectedPlan.getPlanElements.forEach {
          case leg: Leg if leg.getMode.toLowerCase != "car" => leg.setMode("")
          case _                                            =>
        }
    }
  }
}
