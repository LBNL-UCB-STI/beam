package beam.replanning

import javax.inject.Inject

import org.matsim.api.core.v01.population.{Activity, HasPlansAndId, Person, Plan}
import org.matsim.core.config.Config
import org.matsim.core.config.groups.PlanCalcScoreConfigGroup
import org.matsim.core.replanning.{PlanStrategy, ReplanningContext}

import scala.collection.JavaConverters._

class GrabExperiencedPlan @Inject()(config: Config) extends PlanStrategy {

  if (!config.planCalcScore().isMemorizingExperiencedPlans) {
    throw new RuntimeException("Must memorize experienced plans for this to work.")
  }

  override def init(replanningContext: ReplanningContext): Unit = {}

  override def run(person: HasPlansAndId[Plan, Person]): Unit = {
    val experiencedPlan = person.getSelectedPlan.getCustomAttributes.get(PlanCalcScoreConfigGroup.EXPERIENCED_PLAN_KEY).asInstanceOf[Plan]
    if(experiencedPlan != null && experiencedPlan.getPlanElements.size() > 0){
      // BeamMobsim needs activities with coords
      val plannedActivities = person.getSelectedPlan.getPlanElements.asScala.filter(e => e.isInstanceOf[Activity])
      val experiencedActivities = experiencedPlan.getPlanElements.asScala.filter(e => e.isInstanceOf[Activity])
      plannedActivities.zip(experiencedActivities).foreach {
        case (plannedActivity: Activity, experiencedActivity: Activity) =>
          experiencedActivity.setCoord(plannedActivity.getCoord)
      }
      experiencedPlan.getAttributes.putAttribute("modality-style", person.getSelectedPlan.getAttributes.getAttribute("modality-style"))
      experiencedPlan.getAttributes.putAttribute("scores", person.getSelectedPlan.getAttributes.getAttribute("scores"))
      assert(experiencedPlan.getPlanElements.get(0).asInstanceOf[Activity].getCoord != null)
      person.addPlan(experiencedPlan)
    }else{
      person.addPlan(person.getSelectedPlan)
    }
  }

  override def finish(): Unit = {}
}
