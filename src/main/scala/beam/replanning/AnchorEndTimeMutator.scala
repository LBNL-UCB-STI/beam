package beam.replanning

import javax.inject.Inject
import org.matsim.api.core.v01.population._
import org.matsim.core.config.Config

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.language.postfixOps
import scala.util.Random

/**
  * A class that mutates the end time of agent's activities by random values within a fixed range.
  * @param config config object
  */
class AnchorEndTimeMutator @Inject()(config: Config) extends PlansStrategyAdopter {
  import AnchorEndTimeMutator._

  override def run(person: HasPlansAndId[Plan, Person]): Unit = {

    //store the existing state of the selected plans for the person
    if (!isPlanAlreadyStored(person.getId.toString)) {
      storeOriginalSelectedPlan(person.getId.toString, person.getSelectedPlan)
    }

    val planElements: mutable.Seq[PlanElement] = person.getSelectedPlan.getPlanElements.asScala

    // A random value to the added to the existing activity end times
    val randomRange = 10 * (Random.nextDouble() - 0.5)

    //For each activity in the selected plan update the activity end time to the new interval based on the random range
    planElements foreach {
      case activity: Activity =>
        val originalEndTime = activity.getEndTime
        activity.setEndTime(originalEndTime + randomRange)
      case _ =>
    }

    ReplanningUtil.makeExperiencedMobSimCompatible(person)
    ReplanningUtil.copyRandomPlanAndSelectForMutation(person.getSelectedPlan.getPerson)

  }
}

/**
  * A companion object for the [[beam.replanning.AnchorEndTimeMutator]] class
  */
object AnchorEndTimeMutator {

  // A map that temporarily stores the original state of the selected plans for a given person
  private val personSelectedPlans: mutable.HashMap[String, Plan] = mutable.HashMap.empty

  // Stores the given person's original selected plan into the map
  def storeOriginalSelectedPlan(personId: String, plan: Plan): Option[Plan] = {
    personSelectedPlans.put(personId, plan)
  }

  // Checks if the original selected plan is already stored for the person
  def isPlanAlreadyStored(personId: String): Boolean = {
    personSelectedPlans.contains(personId)
  }

  // Gets original selected plans of all the people
  def getOriginalSelectedPlans: mutable.HashMap[String, Plan] = {
    this.personSelectedPlans
  }

  def reset(): Unit = {
    this.personSelectedPlans.clear()
  }

}
