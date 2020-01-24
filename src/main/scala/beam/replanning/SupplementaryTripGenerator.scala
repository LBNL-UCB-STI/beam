package beam.replanning

import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.router.Modes.BeamMode
import beam.router.skim.Skims
import beam.sim.population.AttributesOfIndividual
import beam.agentsim.agents.choice.logit.{DestinationMNL, MultinomialLogit}
import beam.router.Modes.BeamMode.CAR
import org.matsim.api.core.v01.population.{Activity, Plan}
import org.matsim.core.population.PopulationUtils

import scala.collection.immutable.List
import scala.util.Random

class SupplementaryTripGenerator(
  val attributesOfIndividual: AttributesOfIndividual
) {
  val tazChoiceSet = generateTazChoiceSet(20)
  val travelTimeBufferInSec = 30 * 60
  val r = scala.util.Random

  def generateSubtour(
    currentActivity: Activity,
    mnl: MultinomialLogit[DestinationMNL.SupplementaryTripAlternative, DestinationMNL.Parameters]
  ): List[Activity] = {
    if ((currentActivity.getEndTime > 0) & (currentActivity.getStartTime > 0)) {
      val newActivityDuration = 10 * 60
      val (startTime, endTime) = generateSubtourStartAndEndTime(currentActivity, newActivityDuration)

      val tazCosts: Map[DestinationMNL.SupplementaryTripAlternative, Map[DestinationMNL.Parameters, Double]] =
        gatherTazCosts(currentActivity, tazChoiceSet, startTime, endTime)

      val chosenAlternativeOption = mnl.sampleAlternative(tazCosts, r)
      val chosenAlternative = chosenAlternativeOption.get.alternativeType

      val newActivity =
        PopulationUtils.createActivityFromCoord(chosenAlternative.activityType, TAZTreeMap.randomLocationInTAZ(chosenAlternative.taz))
      val activityBeforeNewActivity =
        PopulationUtils.createActivityFromCoord("Work_Before", currentActivity.getCoord)
      val activityAfterNewActivity =
        PopulationUtils.createActivityFromCoord("Work_After", currentActivity.getCoord)

      activityBeforeNewActivity.setStartTime(currentActivity.getStartTime)
      activityBeforeNewActivity.setEndTime(startTime - travelTimeBufferInSec)

      newActivity.setStartTime(startTime)
      newActivity.setEndTime(endTime)

      activityAfterNewActivity.setStartTime(endTime + travelTimeBufferInSec)
      activityAfterNewActivity.setEndTime(currentActivity.getEndTime)

      List(activityBeforeNewActivity, newActivity, activityAfterNewActivity)
    } else {
      List(currentActivity)
    }
  }

  private def gatherTazCosts(
    currentActivity: Activity,
    TAZs: List[TAZ],
    startTime: Int,
    endTime: Int
  ): Map[DestinationMNL.SupplementaryTripAlternative, Map[DestinationMNL.Parameters, Double]] = {
    val tazToCost: Map[DestinationMNL.SupplementaryTripAlternative, Map[DestinationMNL.Parameters, Double]] =
      TAZs.map { taz =>
        val cost =
          getTazCost(currentActivity, taz, BeamMode.CAR, startTime, endTime)
        val alternative = DestinationMNL.SupplementaryTripAlternative(taz, "BLAH", CAR, endTime - startTime, startTime)
        alternative -> DestinationMNL.toUtilityParameters(cost)
      }.toMap
    tazToCost
  }

  private def getTazCost(
    currentActivity: Activity,
    taz: TAZ,
    mode: BeamMode,
    newActivityStartTime: Double,
    newActivityEndTime: Double
  ): DestinationMNL.TimesAndCost = {
    val activityDurationInSeconds = (newActivityEndTime - newActivityStartTime)
    val desiredDepartTimeBin = secondsToIndex(newActivityStartTime)
    val desiredReturnTimeBin = secondsToIndex(newActivityEndTime)
    val accessTripSkim =
      Skims.od_skimmer.getTimeDistanceAndCost(
        currentActivity.getCoord,
        TAZTreeMap.randomLocationInTAZ(taz),
        desiredDepartTimeBin,
        mode
      )
    val egressTripSkim =
      Skims.od_skimmer.getTimeDistanceAndCost(
        TAZTreeMap.randomLocationInTAZ(taz),
        currentActivity.getCoord,
        desiredReturnTimeBin,
        mode
      )
    val startingOverlap =
      (currentActivity.getStartTime - (newActivityStartTime - accessTripSkim.time - travelTimeBufferInSec)).max(0)
    val endingOverlap =
      ((newActivityEndTime + egressTripSkim.time + travelTimeBufferInSec) - currentActivity.getEndTime).max(0)
    val schedulePenalty = math.pow(startingOverlap, 2) + math.pow(endingOverlap, 2)
    DestinationMNL.TimesAndCost(
      accessTripSkim.time,
      egressTripSkim.time,
      attributesOfIndividual.getVOT(accessTripSkim.generalizedTime / 3600) + accessTripSkim.cost,
      attributesOfIndividual.getVOT(egressTripSkim.generalizedTime / 3600) + egressTripSkim.cost,
      schedulePenalty,
      attributesOfIndividual.getVOT(activityDurationInSeconds / 3600)
    )
  }

  private def generateSubtourStartAndEndTime(
    currentActivity: Activity,
    newActivityDuration: Double
  ): (Int, Int) = {
    val currentActivityDuration = currentActivity.getEndTime - currentActivity.getStartTime
    val feasibleWindowDuration = currentActivityDuration - newActivityDuration - 2 * travelTimeBufferInSec
    val startTimeBuffer = r.nextDouble() * feasibleWindowDuration + travelTimeBufferInSec
    (
      (currentActivity.getStartTime + startTimeBuffer).toInt,
      (currentActivity.getStartTime + startTimeBuffer + newActivityDuration).toInt
    )
  }

  private def generateTazChoiceSet(n: Int): List[TAZ] = {
    Random.shuffle(Skims.od_skimmer.beamServices.beamScenario.tazTreeMap.getTAZs.toList).take(n)
  }

  private def secondsToIndex(time: Double): Int = {
    (time / 3600).toInt
  }

}
