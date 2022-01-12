package beam.replanning

import org.matsim.api.core.v01.population.{HasPlansAndId, Leg, Person, Plan}
import org.slf4j.LoggerFactory

class ClearRoutes() extends PlansStrategyAdopter {

  private val log = LoggerFactory.getLogger(classOf[ClearRoutes])

  override def run(person: HasPlansAndId[Plan, Person]): Unit = {
    log.debug("Before Replanning ClearRoutes: Person-" + person.getId + " - " + person.getPlans.size())
    ReplanningUtil.makeExperiencedMobSimCompatible(person)
    ReplanningUtil.copyRandomPlanAndSelectForMutation(person.getSelectedPlan.getPerson)

    person.getSelectedPlan.getPlanElements.forEach {
      case leg: Leg =>
        leg.setRoute(null)
      case _ =>
    }

    log.debug("After Replanning ClearRoutes: Person-" + person.getId + " - " + person.getPlans.size())
  }
}
