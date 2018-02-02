package beam.replanning

import javax.inject.Inject

import org.matsim.api.core.v01.population.{HasPlansAndId, Person, Plan}
import org.matsim.core.config.Config
import org.matsim.core.config.groups.PlanCalcScoreConfigGroup
import org.matsim.core.replanning.{PlanStrategy, ReplanningContext}

class GrabExperiencedPlan @Inject()(config: Config) extends PlanStrategy {

  if(!config.planCalcScore().isMemorizingExperiencedPlans) {
    throw new RuntimeException("Must memorize experienced plans for this to work.")
  }

  override def init(replanningContext: ReplanningContext): Unit = {}
  override def run(person: HasPlansAndId[Plan, Person]): Unit = {
    val experiencedPlan = person.getSelectedPlan.getCustomAttributes.get(PlanCalcScoreConfigGroup.EXPERIENCED_PLAN_KEY).asInstanceOf[Plan]
    assert(experiencedPlan != null)
    person.addPlan(experiencedPlan)
  }
  override def finish(): Unit = {}
}
