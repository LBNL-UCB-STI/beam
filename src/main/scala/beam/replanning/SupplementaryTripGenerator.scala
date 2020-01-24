package beam.replanning

import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.router.Modes.BeamMode
import beam.router.skim.Skims
import beam.sim.population.AttributesOfIndividual
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.{Activity, HasPlansAndId, Person, Plan}
import beam.router.skim.ODSkimmer.Skim
import org.matsim.core.population.PopulationUtils

import scala.collection.immutable.List
import scala.collection.mutable
import scala.util.Random

class SupplementaryTripGenerator(
  val attributesOfIndividual: AttributesOfIndividual
) {
  val tazChoiceSet = generateTazChoiceSet(20)
  val travelTimeBufferInSec = 30 * 60
  val r = scala.util.Random

  def generateSubtour(currentActivity: Activity): List[Activity] = {
    if ((currentActivity.getEndTime > 0) & (currentActivity.getStartTime > 0)) {
      val newActivityDuration = 10 * 60
      val (startTime, endTime) = generateSubtourStartAndEndTime(currentActivity, newActivityDuration)

      val tazCosts = gatherTazCosts(currentActivity, tazChoiceSet, startTime, endTime)

      val chozenTazAndCost = tazCosts.toSeq.sortBy(_._2.cost).apply(3)

      val newActivity =
        PopulationUtils.createActivityFromCoord("IJUSTMADETHIS", TAZTreeMap.randomLocationInTAZ(chozenTazAndCost._1))
      if ((chozenTazAndCost._2.accessTime + chozenTazAndCost._2.returnTime + newActivityDuration + 2 * travelTimeBufferInSec) < (currentActivity.getEndTime - currentActivity.getStartTime)) {
        val activityBeforeNewActivity = PopulationUtils.createActivityFromCoord("Work_Before", currentActivity.getCoord)
        val activityAfterNewActivity = PopulationUtils.createActivityFromCoord("Work_After", currentActivity.getCoord)

        activityBeforeNewActivity.setStartTime(currentActivity.getStartTime)
        activityBeforeNewActivity.setEndTime(startTime - chozenTazAndCost._2.accessTime)

        newActivity.setStartTime(startTime)
        newActivity.setEndTime(endTime)

        activityAfterNewActivity.setStartTime(endTime + chozenTazAndCost._2.returnTime)
        activityAfterNewActivity.setEndTime(currentActivity.getEndTime)

        List(activityBeforeNewActivity, newActivity, activityAfterNewActivity)
      } else {
        List(currentActivity)
      }
    } else {
      List(currentActivity)
    }
  }

  private def gatherTazCosts(
    currentActivity: Activity,
    TAZs: List[TAZ],
    startTime: Double,
    endTime: Double
  ): mutable.Map[TAZ, TimesAndCost] = {
    val tazToCost = mutable.Map.empty[TAZ, TimesAndCost]
    TAZs.foreach { taz =>
      val cost =
        getTazCost(currentActivity, taz, BeamMode.CAR, startTime, endTime)
      tazToCost.put(taz, cost)
    }
    tazToCost
  }

  private def getTazCost(
    currentActivity: Activity,
    taz: TAZ,
    mode: BeamMode,
    newActivityStartTime: Double,
    newActivityEndTime: Double
  ): TimesAndCost = {
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
    val combinedSkim = accessTripSkim + Skim(activityDurationInSeconds.toInt) + egressTripSkim
    TimesAndCost(
      accessTripSkim.time,
      egressTripSkim.time,
      attributesOfIndividual.getVOT(combinedSkim.generalizedTime / 3600) + combinedSkim.cost
    )
  }

  private def generateSubtourStartAndEndTime(
    currentActivity: Activity,
    newActivityDuration: Double
  ): (Double, Double) = {
    val currentActivityDuration = currentActivity.getEndTime - currentActivity.getStartTime
    val feasibleWindowDuration = currentActivityDuration - newActivityDuration - 2 * travelTimeBufferInSec
    val startTimeBuffer = r.nextDouble() * feasibleWindowDuration + travelTimeBufferInSec
    (
      currentActivity.getStartTime + startTimeBuffer,
      currentActivity.getStartTime + startTimeBuffer + newActivityDuration
    )
  }

  private def generateTazChoiceSet(n: Int): List[TAZ] = {
    Random.shuffle(Skims.od_skimmer.beamServices.beamScenario.tazTreeMap.getTAZs.toList).take(n)
  }

  private def secondsToIndex(time: Double): Int = {
    (time / 3600).toInt
  }

  case class TimesAndCost(accessTime: Double = 0, returnTime: Double = 0, cost: Double = 0)
}
