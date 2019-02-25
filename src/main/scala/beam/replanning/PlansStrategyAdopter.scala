package beam.replanning

import org.matsim.api.core.v01.population.{HasPlansAndId, Person, Plan}
import org.matsim.core.replanning.{PlanStrategy, ReplanningContext}

trait PlansStrategyAdopter extends PlanStrategy {
  override def init(replanningContext: ReplanningContext): Unit = {}

  override def finish(): Unit = {}
}
