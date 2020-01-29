package beam.replanning

import beam.agentsim.agents.choice.logit.LatentClassChoiceModel.LccmData
import beam.agentsim.agents.choice.logit.{
  DestinationChoiceModel,
  LatentClassChoiceModel,
  MultinomialLogit,
  UtilityFunctionOperation
}
import beam.sim.population.AttributesOfIndividual
import javax.inject.Inject
import org.matsim.api.core.v01.population.{Activity, HasPlansAndId, Leg, Person, Plan}
import org.matsim.core.config.Config
import org.matsim.core.population.PopulationUtils
import org.matsim.core.utils.io.IOUtils
import org.matsim.utils.objectattributes.attributable.AttributesUtils
import org.slf4j.LoggerFactory
import org.supercsv.cellprocessor.ift.CellProcessor
import org.supercsv.io.CsvBeanReader
import org.supercsv.prefs.CsvPreference

import scala.collection.JavaConverters._
import scala.collection.immutable.List
import scala.collection.mutable

class AddSupplementaryTrips @Inject()(config: Config) extends PlansStrategyAdopter {

  private val log = LoggerFactory.getLogger(classOf[AddSupplementaryTrips])

  override def run(person: HasPlansAndId[Plan, Person]): Unit = {
    log.debug("Before Replanning AddNewActivities: Person-" + person.getId + " - " + person.getPlans.size())
    ReplanningUtil.makeExperiencedMobSimCompatible(person)

//    val destinationMNL
//      : MultinomialLogit[DestinationMNL.SupplementaryTripAlternative, DestinationMNL.DestinationParameters] =
//      new MultinomialLogit(Map.empty, DestinationMNL.DefaultMNLParameters)
//
//    val tripMNL: MultinomialLogit[Boolean, DestinationMNL.TripParameters] =
//      new MultinomialLogit(Map.empty, DestinationMNL.TripMNLParameters)
//
//    val supplementaryTripGenerator = new SupplementaryTripGenerator(
//      person.getSelectedPlan.getPerson.getCustomAttributes.get("beam-attributes").asInstanceOf[AttributesOfIndividual]
//    )

    val simplifiedPlan = mandatoryTour(person.getSelectedPlan)

    val newPlan = ReplanningUtil.addNoModeBeamTripsToPlanWithOnlyActivities(
      addSecondaryActivities(
        simplifiedPlan,
        person.getSelectedPlan.getPerson
      )
    )

    AttributesUtils.copyAttributesFromTo(person.getSelectedPlan, newPlan)

    if (newPlan.getPlanElements.size > 1) {
      person.addPlan(newPlan)
      person.setSelectedPlan(newPlan)
    }

    log.debug("After Replanning AddNewActivities: Person-" + person.getId + " - " + person.getPlans.size())
  }

  private def mandatoryTour(
    plan: Plan
  ): Plan = {

    val newPlan = PopulationUtils.createPlan(plan.getPerson)
    newPlan.setType(plan.getType)

    val elements: List[Activity] = plan.getPlanElements.asScala
      .collect { case activity: Activity => activity }
      .filter(x => (x.getType.equalsIgnoreCase("Work") | x.getType.equalsIgnoreCase("Home")))
      .toList

    val newElements = elements.foldLeft(mutable.MutableList[Activity]())(
      (listOfAct, currentAct) =>
        listOfAct.lastOption match {
          case Some(lastAct) =>
            if (lastAct.getType == currentAct.getType) {
              listOfAct.last.setEndTime(currentAct.getEndTime)
              listOfAct
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
    person: Person
  ): List[Activity] = {
    activity.getType match {
      case "Home" => addSubtourToActivity(activity)
      case "Work" => addSubtourToActivity(activity)
      case _      => List[Activity](activity)
    }
  }

  private def addSubtourToActivity(
    activity: Activity
  ): List[Activity] = {
    val startTime = if (activity.getStartTime > 0) { activity.getStartTime } else { 0 }
    val endTime = if (activity.getEndTime > 0) { activity.getEndTime } else { 3600 * 24 }

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

  private def addSecondaryActivities(
    plan: Plan,
    person: Person
  ): Plan = {
    val newPlan = PopulationUtils.createPlan(plan.getPerson)
    newPlan.setType(plan.getType)

    val elements = plan.getPlanElements.asScala.collect { case activity: Activity => activity }
    val newActivitiesToAdd = elements.zipWithIndex.map {
      case (planElement, idx) =>
        val prevEndTime = if (idx > 0) {
          elements(idx - 1).getEndTime.max(0)
        } else {
          0
        }
        planElement.setMaximumDuration(planElement.getEndTime - prevEndTime)
        definitelyAddSubtours(planElement, person)
    }
    newActivitiesToAdd.flatten.foreach { x =>
      newPlan.addActivity(x)
    }
    newPlan
  }

}
