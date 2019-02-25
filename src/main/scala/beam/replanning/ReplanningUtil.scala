package beam.replanning

import beam.utils.DebugLib
import org.matsim.api.core.v01.population._
import org.matsim.core.config.groups.PlanCalcScoreConfigGroup
import org.matsim.core.population.PopulationUtils
import org.matsim.core.replanning.selectors.RandomPlanSelector
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._

object ReplanningUtil {

  def makeExperiencedMobSimCompatible[T <: Plan, I](person: HasPlansAndId[T, I]): Unit = {
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
          experiencedActivity.setEndTime(plannedActivity.getEndTime)
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

      copyRemainingPlanElementsIfExperiencedPlanIncomplete(person.getSelectedPlan, experiencedPlan)

      person.asInstanceOf[Person].addPlan(experiencedPlan)
      person.removePlan(person.getSelectedPlan)
      person.asInstanceOf[Person].setSelectedPlan(experiencedPlan)
    }
  }

  def copyRemainingPlanElementsIfExperiencedPlanIncomplete(originalPlan: Plan, experiencedPlan: Plan): Unit = {

    if (originalPlan.getPlanElements.size() > experiencedPlan.getPlanElements.size()) {
      DebugLib.emptyFunctionForSettingBreakPoint()
      for (i <- experiencedPlan.getPlanElements.size() until originalPlan.getPlanElements.size()) {
        originalPlan.getPlanElements.get(i) match {
          case activity: Activity =>
            experiencedPlan.addActivity(
              PopulationUtils.createActivity(activity)
            )
          case _ =>
            experiencedPlan.addLeg(PopulationUtils.createLeg(originalPlan.getPlanElements.get(i).asInstanceOf[Leg]))
        }
      }
      DebugLib.emptyFunctionForSettingBreakPoint()
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
