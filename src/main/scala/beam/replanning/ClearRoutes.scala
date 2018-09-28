package beam.replanning

import javax.inject.Inject

import com.google.inject.Provider
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.{HasPlansAndId, Leg, Person, Plan}
import org.matsim.api.core.v01.replanning.PlanStrategyModule
import org.matsim.core.config.Config
import org.matsim.core.population.PopulationUtils
import org.matsim.core.replanning.selectors.RandomPlanSelector
import org.matsim.core.replanning.{PlanStrategy, PlanStrategyImpl, ReplanningContext}

class ClearRoutes @Inject()(config: Config) extends PlanStrategy {
  override def init(replanningContext: ReplanningContext): Unit = {}

  override def run(person: HasPlansAndId[Plan, Person]): Unit = {
    ReplanningUtil.updateAndAddExperiencedPlan(person)
    ReplanningUtil.copyRandomPlanAndSelectForMutation(person.getSelectedPlan.getPerson)

    person.getSelectedPlan.getPlanElements.forEach {
      case leg: Leg =>
        leg.setRoute(null)
      case _ =>
    }
  }

  override def finish(): Unit = {}
}
