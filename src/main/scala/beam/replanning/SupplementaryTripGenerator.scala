package beam.replanning

import beam.agentsim.infrastructure.taz.TAZ
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

  def generateSubtour(currentActivity: Activity): List[Activity] = {
    if ((currentActivity.getEndTime > 0) & (currentActivity.getStartTime > 0)){
      val (startTime, endTime) = generateSubtourStartAndEndTime(currentActivity)
      val newActivity = PopulationUtils.createActivityFromCoord("IJUSTMADETHIS", currentActivity.getCoord)
      val activityBeforeNewActivity = PopulationUtils.createActivityFromCoord("Work_Before", currentActivity.getCoord)
      val activityAfterNewActivity = PopulationUtils.createActivityFromCoord("Work_After", currentActivity.getCoord)

      activityBeforeNewActivity.setStartTime(currentActivity.getStartTime)
      activityBeforeNewActivity.setEndTime(startTime)

      newActivity.setStartTime(startTime)
      newActivity.setEndTime(endTime)

      activityAfterNewActivity.setStartTime(endTime)
      activityAfterNewActivity.setEndTime(currentActivity.getEndTime)

      List(activityBeforeNewActivity, newActivity, activityAfterNewActivity)
    } else {
      List(currentActivity)
    }

  }

  def getTazTimeDistanceCost(currentActivity:Activity, TAZs: List[TAZ]): mutable.Map[Id[TAZ], Skim] = {
    val tazToCost = mutable.Map.empty[Id[TAZ], Skim]
    val startTimeInt = math.floor(currentActivity.getStartTime / 3600).toInt
    TAZs.foreach {
      taz =>
        val skim = Skims.od_skimmer.getTimeDistanceAndCost(currentActivity.getCoord, taz.coord, startTimeInt, BeamMode.CAR)
        tazToCost.put(taz.tazId, skim)
    }
    tazToCost
  }

  def generateSubtourStartAndEndTime(currentActivity: Activity): (Double, Double) = {
    val maxDuration = currentActivity.getMaximumDuration.max(currentActivity.getEndTime - currentActivity.getStartTime).min(300)
    (currentActivity.getStartTime + maxDuration / 3, currentActivity.getEndTime - maxDuration / 3)
  }

  def generateTazChoiceSet(n: Int): List[TAZ] = {
    Random.shuffle(Skims.od_skimmer.beamServices.beamScenario.tazTreeMap.getTAZs.toList).take(n)
  }
}
