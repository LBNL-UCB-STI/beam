package beam.replanning

import javax.inject.Inject
import org.matsim.api.core.v01.population.{Activity, HasPlansAndId, Leg, Person, Plan}
import org.matsim.core.config.Config
import org.matsim.core.population.PopulationUtils
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.collection.immutable.List

class AddSupplementaryTrips @Inject()(config: Config) extends PlansStrategyAdopter {

  private val log = LoggerFactory.getLogger(classOf[AddSupplementaryTrips])

  override def run(person: HasPlansAndId[Plan, Person]): Unit = {
    log.debug("Before Replanning AddNewActivities: Person-" + person.getId + " - " + person.getPlans.size())
    ReplanningUtil.makeExperiencedMobSimCompatible(person)
    ReplanningUtil.copyRandomPlanAndSelectForMutation(person.getSelectedPlan.getPerson)

    config.subtourModeChoice()

    val newPlan = ReplanningUtil.addNoModeBeamTripsToPlanWithOnlyActivities(
      addSecondaryActivities(person.getSelectedPlan, person.getSelectedPlan.getPerson)
    )

    newPlan.getPlanElements.forEach {
      case leg: Leg =>
        leg.setMode("")
      case _ =>
    }
    person.addPlan(newPlan)
    person.setSelectedPlan(newPlan)

    ReplanningUtil.makeExperiencedMobSimCompatible(person)

    log.debug("After Replanning AddNewActivities: Person-" + person.getId + " - " + person.getPlans.size())
  }

  private def possiblyAddSubtour(activity: Activity, person: Person): List[Activity] = {
    val maxdur = activity.getMaximumDuration
    activity.getType match {
      case "Home" => List[Activity](activity)
      case "Work" =>
        val newActivity = PopulationUtils.createActivityFromCoord("IJUSTMADETHIS", activity.getCoord)
        newActivity.setEndTime(activity.getEndTime - maxdur / 3)
        val activityBeforeNewActivity = PopulationUtils.createActivityFromCoord("Work2", activity.getCoord)
        activityBeforeNewActivity.setEndTime(activity.getEndTime - 2 * maxdur / 3)
        activityBeforeNewActivity.setStartTime(activity.getStartTime)
        activity.setStartTime(Double.NegativeInfinity)
        List(activityBeforeNewActivity, newActivity, activity)
      case _ => List[Activity](activity)
    }
  }

  private def addSecondaryActivities(plan: Plan, person: Person): Plan = {
    val newPlan = PopulationUtils.createPlan(plan.getPerson)
    val elements = plan.getPlanElements.asScala.collect { case activity: Activity => activity }
    val newActivitiesToAdd = elements.zipWithIndex.map {
      case (planElement, idx) =>
        val prevEndTime = if (idx > 0) {
          elements(idx - 1).getEndTime
        } else {
          0
        }
        planElement.setMaximumDuration(planElement.getEndTime - prevEndTime)
        possiblyAddSubtour(planElement, person)
    }
    newActivitiesToAdd.flatten.foreach { x =>
      newPlan.addActivity(x)
    }
    newPlan
  }
}
