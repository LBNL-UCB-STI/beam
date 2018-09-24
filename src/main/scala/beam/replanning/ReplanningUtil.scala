package beam.replanning

import org.matsim.api.core.v01.population.{Activity, HasPlansAndId, Person, Plan}
import org.matsim.core.config.groups.PlanCalcScoreConfigGroup
import org.matsim.core.population.PopulationUtils
import org.matsim.core.replanning.selectors.RandomPlanSelector

import scala.collection.JavaConverters._

object ReplanningUtil {

  def updateAndAddExperiencedPlan[T <: Plan, I](person: HasPlansAndId[T, I]): Unit = {
    val experiencedPlan = person.getSelectedPlan.getCustomAttributes
      .get(PlanCalcScoreConfigGroup.EXPERIENCED_PLAN_KEY)
      .asInstanceOf[Plan]

    if (experiencedPlan != null && experiencedPlan.getPlanElements.size() > 0) {
      // BeamMobsim needs activities with coords
      val plannedActivities =
        person.getSelectedPlan.getPlanElements.asScala.filter(e => e.isInstanceOf[Activity])
      val experiencedActivities =
        experiencedPlan.getPlanElements.asScala.filter(e => e.isInstanceOf[Activity])
      plannedActivities.zip(experiencedActivities).foreach {
        case (plannedActivity: Activity, experiencedActivity: Activity) =>
          experiencedActivity.setCoord(plannedActivity.getCoord)
        case (_, _) =>
      }
      val attributes = experiencedPlan.getAttributes
      val selectedPlanAttributes = person.getSelectedPlan.getAttributes
      attributes.putAttribute(
        "modality-style",
        selectedPlanAttributes.getAttribute("modality-style")
      )
      attributes.putAttribute("scores", selectedPlanAttributes.getAttribute("scores"))
      assert(experiencedPlan.getPlanElements.get(0).asInstanceOf[Activity].getCoord != null)
      person.asInstanceOf[Person].addPlan(experiencedPlan)
      person.removePlan(person.getSelectedPlan)
      person.asInstanceOf[Person].setSelectedPlan(experiencedPlan)
    } else {
      person.addPlan(person.getSelectedPlan)
    }
  }

  def copyRandomPlanAndSelectForMutation(person: Person): Unit = {
    person.setSelectedPlan(new RandomPlanSelector().selectPlan(person))
    val newPlan = PopulationUtils.createPlan(person.getSelectedPlan.getPerson)
    PopulationUtils.copyFromTo(person.getSelectedPlan, newPlan)
    person.addPlan(newPlan)
    person.setSelectedPlan(newPlan)
  }
}
