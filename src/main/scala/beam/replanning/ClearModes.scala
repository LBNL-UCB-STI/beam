package beam.replanning

import org.matsim.api.core.v01.population.{HasPlansAndId, Leg, Person, Plan}
import org.slf4j.LoggerFactory

class ClearModes() extends PlansStrategyAdopter {
  private val log = LoggerFactory.getLogger(classOf[ClearModes])

  override def run(person: HasPlansAndId[Plan, Person]): Unit = {
    log.debug("Before Replanning ClearModes: Person-" + person.getId + " - " + person.getPlans.size())
    ReplanningUtil.makeExperiencedMobSimCompatible(person)
    ReplanningUtil.copyRandomPlanAndSelectForMutation(person.getSelectedPlan.getPerson)

    person.getSelectedPlan.getPlanElements.forEach {
      case leg: Leg =>
        leg.setMode("")
      case _ =>
    }
    log.debug("After Replanning ClearModes: Person-" + person.getId + " - " + person.getPlans.size())
  }
}
