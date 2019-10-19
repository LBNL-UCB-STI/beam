package beam.replanning

import com.google.inject.Inject
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.population.Population
import org.matsim.core.controler.events.ReplanningEvent
import org.matsim.core.controler.listener.ReplanningListener
import org.matsim.core.replanning.StrategyManager

class BeamReplanning @Inject()(population: Population, strategyManager: StrategyManager) extends ReplanningListener with LazyLogging {
  override def notifyReplanning(event: ReplanningEvent): Unit = {
    strategyManager.run(population, () => event.getIteration)
  }
}
