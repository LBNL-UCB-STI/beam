package beam.replanning

import javax.inject.Inject
import org.matsim.api.core.v01.population.{HasPlansAndId, Leg, Person, Plan}
import org.matsim.core.config.Config

class ClearRoutes @Inject()(config: Config) extends PlansStrategyAdopter {
  override def run(person: HasPlansAndId[Plan, Person]): Unit = {
    ReplanningUtil.updateAndAddExperiencedPlan(person)
    ReplanningUtil.copyRandomPlanAndSelectForMutation(person.getSelectedPlan.getPerson)

    person.getSelectedPlan.getPlanElements.forEach {
      case leg: Leg =>
        leg.setRoute(null)
      case _ =>
    }
  }
}
