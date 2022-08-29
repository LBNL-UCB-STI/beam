package beam.replanning

import beam.sim.config.BeamConfig
import javax.inject.Inject
import org.matsim.api.core.v01.population.{Activity, HasPlansAndId, Person, Plan}
import org.matsim.core.population.PopulationUtils
import org.matsim.utils.objectattributes.attributable.AttributesUtils
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.collection.immutable.List
import scala.collection.mutable

class AddSupplementaryTrips @Inject() (beamConfig: BeamConfig) extends PlansStrategyAdopter {

  private val log = LoggerFactory.getLogger(classOf[AddSupplementaryTrips])

  override def run(person: HasPlansAndId[Plan, Person]): Unit = {
    if (beamConfig.beam.agentsim.agents.tripBehaviors.mulitnomialLogit.generate_secondary_activities) {
      log.debug("Before Replanning AddNewActivities: Person-" + person.getId + " - " + person.getPlans.size())
      ReplanningUtil.makeExperiencedMobSimCompatible(person)

      val simplifiedPlan = mandatoryTour(person.getSelectedPlan)

      val newPlan = ReplanningUtil.addNoModeBeamTripsToPlanWithOnlyActivities(
        addSecondaryActivities(simplifiedPlan)
      )

      AttributesUtils.copyAttributesFromTo(person.getSelectedPlan, newPlan)

      if (newPlan.getPlanElements.size > 1) {
        person.addPlan(newPlan)
        person.setSelectedPlan(newPlan)
      }

      log.debug("After Replanning AddNewActivities: Person-" + person.getId + " - " + person.getPlans.size())
    }
  }

  private def mandatoryTour(
    plan: Plan
  ): Plan = {

    val newPlan = PopulationUtils.createPlan(plan.getPerson)
    newPlan.setType(plan.getType)

    val elements: List[Activity] = plan.getPlanElements.asScala
      .collect { case activity: Activity => activity }
      .filter(x => x.getType.equalsIgnoreCase("Work") | x.getType.equalsIgnoreCase("Home"))
      .toList

    val newElements = elements.foldLeft(mutable.MutableList[Activity]())((listOfAct, currentAct) =>
      listOfAct.lastOption match {
        case Some(lastAct) =>
          if (lastAct.getType == currentAct.getType) {
            val lastActivity = PopulationUtils.createActivity(lastAct)
            lastActivity.setEndTime(currentAct.getEndTime)
            val newList = listOfAct.dropRight(1)
            newList :+ lastActivity
          } else {
            listOfAct += currentAct
          }
        case None => mutable.MutableList[Activity](currentAct)
      }
    )

    newElements.foreach { x =>
      newPlan.addActivity(x)
    }
    newPlan
  }

  private def definitelyAddSubtours(
    activity: Activity,
    nonWorker: Boolean = false
  ): List[Activity] = {
    val listOfActivities = activity.getType match {
      case "Home" => addSubtourToActivity(activity)
      case "Work" => addSubtourToActivity(activity)
      case _      => List[Activity](activity)
    }
    if (nonWorker) {
      listOfActivities.flatMap(activity =>
        activity.getType match {
          case "Home" => addSubtourToActivity(activity)
          case "Work" => List[Activity](activity)
          case _      => List[Activity](activity)
        }
      )
    } else {
      listOfActivities
    }
  }

  private def addSubtourToActivity(
    activity: Activity
  ): List[Activity] = {
    val startTime = if (activity.getStartTime > 0) { activity.getStartTime }
    else { 0 }
    val endTime = if (activity.getEndTime > 0) { activity.getEndTime }
    else { 3600 * 24 }

    val newStartTime = (endTime - startTime) / 2 - 1 + startTime
    val newEndTime = (endTime - startTime) / 2 + 1 + startTime

    val newActivity =
      PopulationUtils.createActivityFromCoord(
        "Temp",
        activity.getCoord
      )
    newActivity.setStartTime(newStartTime)
    newActivity.setEndTime(newEndTime)

    val activityBeforeNewActivity =
      PopulationUtils.createActivityFromCoord(activity.getType, activity.getCoord)
    val activityAfterNewActivity =
      PopulationUtils.createActivityFromCoord(activity.getType, activity.getCoord)

    activityBeforeNewActivity.setStartTime(activity.getStartTime)
    activityBeforeNewActivity.setEndTime(newStartTime)

    activityAfterNewActivity.setStartTime(newEndTime)
    activityAfterNewActivity.setEndTime(activity.getEndTime)

    List(activityBeforeNewActivity, newActivity, activityAfterNewActivity)
  }

  private def addSecondaryActivities(plan: Plan): Plan = {
    val newPlan = PopulationUtils.createPlan(plan.getPerson)
    newPlan.setType(plan.getType)

    val elements = plan.getPlanElements.asScala.collect { case activity: Activity => activity }
    val nonWorker = elements.length == 1
    val newActivitiesToAdd = elements.zipWithIndex.map { case (planElement, idx) =>
      val prevEndTime = if (idx > 0) {
        (elements(idx - 1).getEndTime + 1).max(0)
      } else {
        0
      }
      planElement.setMaximumDuration(planElement.getEndTime - prevEndTime)
      planElement.setStartTime(prevEndTime)
      definitelyAddSubtours(planElement, nonWorker)
    }
    newActivitiesToAdd.flatten.foreach { x =>
      newPlan.addActivity(x)
    }
    newPlan
  }

}
