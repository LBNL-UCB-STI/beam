package beam.replanning

import beam.sim.config.BeamConfig
import javax.inject.Inject
import org.matsim.api.core.v01.population._

import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.language.postfixOps
import scala.util.Random

/**
  * @author Bhavya Latha Bandaru.
  * A class that mutates the end time of agent's activities by random values within a fixed range.
  * @param beamConfig An instance of beam config.
  */
class AnchorEndTimeMutator @Inject()(beamConfig: BeamConfig) extends PlansStrategyAdopter {
  import AnchorEndTimeMutator._

  override def run(person: HasPlansAndId[Plan, Person]): Unit = {

    //store the existing state of the selected plans for the person
    if (!isEndTimesAlreadyStored(person.getId.toString)) {
      storeActivitiesOriginalEndTime(person.getId.toString, person.getSelectedPlan)
    }

    // A random value to the added to the existing activity end times
    val randomRange = beamConfig.beam.replanning.anchorEndTimeMutator.mutation.range * (Random.nextDouble() - 0.5)

    val originalEndTimes = getActivitiesOriginalEndTime(person.getId.toString)

    //For each activity in the selected plan update the activity end time to the new interval based on the random range
    (person.getSelectedPlan.getPlanElements.asScala zipWithIndex) foreach { elementWithIndex =>
      val (element, index) = elementWithIndex
      element match {
        case activity: Activity =>
          activity.setEndTime(originalEndTimes(index) + randomRange)
        case _ =>
      }
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
  private val personSelectedPlans: TrieMap[String, Seq[Double]] = TrieMap.empty

  // Stores the given person's original selected plan into the map
  def storeActivitiesOriginalEndTime(personId: String, plan: Plan): Unit = {
    val planElements: mutable.Seq[PlanElement] = plan.getPlanElements.asScala
    val endTimes: mutable.Seq[Double] = planElements flatMap {
      case activity: Activity =>
        Some(activity.getEndTime)
      case _ =>
        None
    }
    personSelectedPlans.put(personId, endTimes)
  }

  // Checks if the original selected plan is already stored for the person
  def isEndTimesAlreadyStored(personId: String): Boolean = {
    personSelectedPlans.contains(personId)
  }

  // Gets original selected plans of all the people
  def getActivitiesOriginalEndTime(personId: String): Seq[Double] = {
    this.personSelectedPlans.getOrElse(personId, Seq.empty)
  }

  def reset(): Unit = {
    this.personSelectedPlans.clear()
  }

}
