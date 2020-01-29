package beam.replanning

import beam.agentsim.agents.choice.logit
import beam.agentsim.agents.choice.logit.DestinationChoiceModel.TripParameters.{ASC, ExpMaxUtility}
import beam.agentsim.agents.choice.logit.DestinationChoiceModel.{
  ActivityRates,
  ActivityVOTs,
  DestinationParameters,
  SupplementaryTripAlternative,
  TimesAndCost,
  TripParameters
}
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.router.Modes.BeamMode
import beam.router.skim.Skims
import beam.sim.population.AttributesOfIndividual
import beam.agentsim.agents.choice.logit.{DestinationChoiceModel, MultinomialLogit}
import beam.router.Modes.BeamMode.CAR
import beam.sim.BeamServices
import org.matsim.api.core.v01.population.{Activity, Plan}
import org.matsim.core.population.PopulationUtils

import scala.collection.JavaConverters._
import scala.collection.immutable.List
import scala.util.Random

class SupplementaryTripGenerator(
  val attributesOfIndividual: AttributesOfIndividual,
  val activityRates: ActivityRates,
  val activityVOTs: ActivityVOTs,
  val beamServices: BeamServices
) {
  val tazChoiceSet = generateTazChoiceSet(20)
  val travelTimeBufferInSec = 30 * 60
  val r = scala.util.Random

  def generateNewPlans(
    plan: Plan,
    destinationMNL: MultinomialLogit[SupplementaryTripAlternative, DestinationParameters],
    tripMNL: MultinomialLogit[Boolean, TripParameters]
  ): Option[Plan] = {
    val newPlan = PopulationUtils.createPlan(plan.getPerson)
    var anyChanges = false
    newPlan.setType(plan.getType)

    val elements = plan.getPlanElements.asScala.collect { case activity: Activity => activity }.toList

    if (!elements(1).getType.equalsIgnoreCase("temp")) { newPlan.addActivity(elements.head) }

    elements.sliding(3).foreach {
      case List(prev, curr, next) =>
        if (curr.getType.equalsIgnoreCase("temp")) {
          anyChanges = true
          val newActivities = generateSubtour(prev, curr, next, destinationMNL, tripMNL)
          newActivities.foreach { x =>
            newPlan.addActivity(x)
          }
        } else {
          if ((!prev.getType.equalsIgnoreCase("temp")) & (!next.getType.equalsIgnoreCase("temp"))) {
            newPlan.addActivity(curr)
          }
        }
      case _ =>
    }

    if (!elements(elements.size - 2).getType.equalsIgnoreCase("temp")) { newPlan.addActivity(elements.last) }

    if (anyChanges) {
      newPlan.setScore(plan.getScore)
      newPlan.setType(plan.getType)
      Some(ReplanningUtil.addNoModeBeamTripsToPlanWithOnlyActivities(newPlan))
    } else {
      None
    }
  }

  private def generateSubtour(
    prevActivity: Activity,
    currentActivity: Activity,
    nextActivity: Activity,
    mnl: MultinomialLogit[SupplementaryTripAlternative, DestinationParameters],
    tripMnl: MultinomialLogit[Boolean, TripParameters]
  ): List[Activity] = {
    val alternativeActivity = PopulationUtils.createActivityFromCoord(prevActivity.getType, currentActivity.getCoord)
    alternativeActivity.setStartTime(prevActivity.getStartTime)
    alternativeActivity.setEndTime(nextActivity.getEndTime)
    if ((currentActivity.getEndTime > 0) & (currentActivity.getStartTime > 0)) {
      val meanActivityDuration = 10 * 60

      val (startTime, endTime) = generateSubtourStartAndEndTime(alternativeActivity, meanActivityDuration)
      val (
        tazCosts: Map[SupplementaryTripAlternative, Map[DestinationParameters, Double]],
        noTrip: Map[TripParameters, Double]
      ) =
        gatherTazCosts(currentActivity, tazChoiceSet, startTime, endTime, alternativeActivity)

      val maxExpectedUtility = mnl.getExpectedMaximumUtility(tazCosts)

      val tripChoice: Map[Boolean, Map[TripParameters, Double]] =
        Map[Boolean, Map[TripParameters, Double]](
          true -> Map[TripParameters, Double](
            TripParameters.ExpMaxUtility -> maxExpectedUtility.getOrElse(0)
          ),
          false -> noTrip,
        )

      val makeTrip: Boolean = tripMnl.sampleAlternative(tripChoice, r).get.alternativeType

      val chosenAlternativeOption = if (makeTrip) {
        mnl.sampleAlternative(tazCosts, r)
      } else {
        None
      }

      chosenAlternativeOption match {
        case Some(outcome) =>
          val chosenAlternative = outcome.alternativeType

          val newActivity =
            PopulationUtils.createActivityFromCoord(
              "NEW",
              TAZTreeMap.randomLocationInTAZ(chosenAlternative.taz)
            )
          val activityBeforeNewActivity =
            PopulationUtils.createActivityFromCoord(prevActivity.getType, prevActivity.getCoord)
          val activityAfterNewActivity =
            PopulationUtils.createActivityFromCoord(nextActivity.getType, nextActivity.getCoord)

          activityBeforeNewActivity.setStartTime(alternativeActivity.getStartTime)
          activityBeforeNewActivity.setEndTime(startTime - travelTimeBufferInSec)

          newActivity.setStartTime(startTime)
          newActivity.setEndTime(endTime)

          activityAfterNewActivity.setStartTime(endTime + travelTimeBufferInSec)
          activityAfterNewActivity.setEndTime(alternativeActivity.getEndTime)

          List(activityBeforeNewActivity, newActivity, activityAfterNewActivity)
        case None =>
          List(alternativeActivity)
      }
    } else {
      List(alternativeActivity)
    }
  }

  private def gatherTazCosts(
    currentActivity: Activity,
    TAZs: List[TAZ],
    startTime: Int,
    endTime: Int,
    alternativeActivity: Activity
  ): (
    Map[SupplementaryTripAlternative, Map[DestinationParameters, Double]],
    Map[TripParameters, Double]
  ) = {
    val (altStart, altEnd) = getRealStartEndTime(alternativeActivity)
    val alternativeActivityCost =
      attributesOfIndividual.getVOT((altEnd - altStart) / 3600)
    val alternativeActivityParamMap = Map[DestinationChoiceModel.TripParameters, Double](
      ExpMaxUtility -> alternativeActivityCost * activityVOTs
        .getOrElse(alternativeActivity.getType, 1.0)
    )

    val tazToCost: Map[SupplementaryTripAlternative, Map[DestinationParameters, Double]] =
      if (TAZs.isEmpty) {
        Map[SupplementaryTripAlternative, Map[DestinationParameters, Double]]()
      } else {
        TAZs.map { taz =>
          val cost =
            getTazCost(currentActivity, taz, BeamMode.CAR, startTime, endTime, alternativeActivity)
          val alternative =
            DestinationChoiceModel.SupplementaryTripAlternative(
              taz,
              currentActivity.getType,
              CAR,
              endTime - startTime,
              startTime
            )
          alternative -> DestinationChoiceModel.toUtilityParameters(cost)
        }.toMap
      }
    (tazToCost, alternativeActivityParamMap)
  }

  private def getRealStartEndTime(
    activity: Activity
  ): (Double, Double) = {
    val start = if (activity.getStartTime > 0) { activity.getStartTime } else { 0 }
    val end = if (activity.getEndTime > 0) { activity.getEndTime } else { 3600 * 24 }
    (start, end)
  }

  private def getTazCost(
    newActivity: Activity,
    taz: TAZ,
    mode: BeamMode,
    newActivityStartTime: Double,
    newActivityEndTime: Double,
    alternativeActivity: Activity
  ): DestinationChoiceModel.TimesAndCost = {
    val (altStart, altEnd) = getRealStartEndTime(alternativeActivity)
    val alternativeActivityDuration = altEnd - altStart
    val activityDuration = newActivityEndTime - newActivityStartTime
    val desiredDepartTimeBin = secondsToIndex(newActivityStartTime)
    val desiredReturnTimeBin = secondsToIndex(newActivityEndTime)
    val accessTripSkim =
      Skims.od_skimmer.getTimeDistanceAndCost(
        newActivity.getCoord,
        TAZTreeMap.randomLocationInTAZ(taz),
        desiredDepartTimeBin,
        mode
      )
    val egressTripSkim =
      Skims.od_skimmer.getTimeDistanceAndCost(
        TAZTreeMap.randomLocationInTAZ(taz),
        newActivity.getCoord,
        desiredReturnTimeBin,
        mode
      )
    val startingOverlap =
      (altStart - (newActivityStartTime - accessTripSkim.time - travelTimeBufferInSec)).max(0)
    val endingOverlap =
      ((newActivityEndTime + egressTripSkim.time + travelTimeBufferInSec) - altEnd).max(0)
    val schedulePenalty = math.pow(startingOverlap, 2) + math.pow(endingOverlap, 2)
    val previousActivityBenefit = attributesOfIndividual.getVOT(
      (alternativeActivityDuration - accessTripSkim.time - egressTripSkim.time - activityDuration) / 3600 * activityVOTs
        .getOrElse(alternativeActivity.getType, 1.0)
    )
    val asc: Double =
      activityRates.getOrElse(desiredDepartTimeBin, Map[String, Double]()).getOrElse(newActivity.getType, 0)
    val newActivityBenefit: Double = attributesOfIndividual.getVOT(
      activityDuration / 3600 * activityVOTs.getOrElse(newActivity.getType, 1.0)
    ) + asc

    TimesAndCost(
      accessTripSkim.time,
      egressTripSkim.time,
      attributesOfIndividual.getVOT(accessTripSkim.generalizedTime / 3600) + accessTripSkim.cost,
      attributesOfIndividual.getVOT(egressTripSkim.generalizedTime / 3600) + egressTripSkim.cost,
      schedulePenalty,
      newActivityBenefit + previousActivityBenefit
    )
  }

  private def generateSubtourStartAndEndTime(
    alternativeActivity: Activity,
    meanActivityDuration: Double
  ): (Int, Int) = {
    val newActivityDuration = -math.log(r.nextDouble()) * meanActivityDuration
    val (altStart, altEnd) = getRealStartEndTime(alternativeActivity)
    val alternativeActivityDuration = altEnd - altStart
    val feasibleWindowDuration = alternativeActivityDuration - newActivityDuration - 2 * travelTimeBufferInSec
    val startTimeBuffer = r.nextDouble() * feasibleWindowDuration + travelTimeBufferInSec
    (
      (altStart + startTimeBuffer).toInt,
      (altStart + startTimeBuffer + newActivityDuration).toInt
    )
  }

  private def generateTazChoiceSet(n: Int): List[TAZ] = {
    Random.shuffle(Skims.od_skimmer.beamServices.beamScenario.tazTreeMap.getTAZs.toList).take(n)
  }

  private def secondsToIndex(time: Double): Int = {
    (time / 3600).toInt
  }

}
