package beam.replanning

import beam.agentsim.agents.choice.logit.DestinationChoiceModel.TripParameters.ExpMaxUtility
import beam.agentsim.agents.choice.logit.DestinationChoiceModel._
import beam.agentsim.agents.choice.logit.{DestinationChoiceModel, MultinomialLogit}
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{CAR, CAV, RIDE_HAIL, RIDE_HAIL_POOLED, WALK, WALK_TRANSIT}
import beam.router.skim.Skims
import beam.sim.BeamServices
import beam.sim.population.AttributesOfIndividual
import com.conveyal.r5.profile.StreetMode
import beam.utils.scenario.PlanElement
import org.matsim.api.core.v01.population.{Activity, Person, Plan}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.population.PopulationUtils
import org.matsim.utils.objectattributes.attributable.AttributesUtils

import scala.collection.JavaConverters._
import scala.collection.immutable.List
import scala.collection.mutable.ListBuffer
import scala.util.Random

class SupplementaryTripGenerator(
  val attributesOfIndividual: AttributesOfIndividual,
  val destinationChoiceModel: DestinationChoiceModel,
  val beamServices: BeamServices,
  val personId: Id[Person]
) {
  val r: Random.type = scala.util.Random
  val personSpecificSeed: Long = personId.hashCode().toLong

  val travelTimeBufferInSec: Int = 30 * 60

  val activityRates: ActivityRates = destinationChoiceModel.activityRates
  val activityVOTs: ActivityVOTs = destinationChoiceModel.activityVOTs
  val activityDurations: ActivityDurations = destinationChoiceModel.activityDurations

  def generateNewPlans(
    plan: Plan,
    destinationChoiceModel: DestinationChoiceModel,
    modes: List[BeamMode] = List[BeamMode](CAR)
  ): Option[Plan] = {

    val modeMNL: MultinomialLogit[
      SupplementaryTripAlternative,
      DestinationChoiceModel.DestinationParameters
    ] =
      MultinomialLogit(
        Map.empty,
        destinationChoiceModel.DefaultMNLParameters,
        beamServices.beamConfig.beam.agentsim.agents.tripBehaviors.mulitnomialLogit.mode_nest_scale_factor
      )

    val destinationMNL: MultinomialLogit[
      SupplementaryTripAlternative,
      DestinationChoiceModel.TripParameters
    ] =
      MultinomialLogit(
        Map.empty,
        destinationChoiceModel.TripMNLParameters,
        beamServices.beamConfig.beam.agentsim.agents.tripBehaviors.mulitnomialLogit.destination_nest_scale_factor
      )

    val tripMNL: MultinomialLogit[Boolean, DestinationChoiceModel.TripParameters] =
      MultinomialLogit(
        Map.empty,
        destinationChoiceModel.TripMNLParameters,
        beamServices.beamConfig.beam.agentsim.agents.tripBehaviors.mulitnomialLogit.trip_nest_scale_factor
      )

    val newPlan = PopulationUtils.createPlan(plan.getPerson)
    var anyChanges = false
    newPlan.setType(plan.getType)

    val elements = plan.getPlanElements.asScala.collect { case activity: Activity => activity }.toList

    if (!elements(1).getType.equalsIgnoreCase("temp")) { newPlan.addActivity(elements.head) }

    var updatedPreviousActivity = elements.head

    val activityAccumulator = ListBuffer[Activity]()

    elements.sliding(3).foreach {
      case List(prev, curr, next) =>
        if (curr.getType.equalsIgnoreCase("temp")) {
          anyChanges = true
          val newActivities =
            generateSubtour(updatedPreviousActivity, curr, next, modeMNL, destinationMNL, tripMNL, modes)
          newActivities.foreach { x =>
            activityAccumulator.lastOption match {
              case Some(lastTrip) =>
                if (lastTrip.getType == x.getType) {
                  activityAccumulator -= activityAccumulator.last
                }
              case _ =>
            }
            activityAccumulator.append(x)
          }
          updatedPreviousActivity = activityAccumulator.last
        } else {
          if ((!prev.getType.equalsIgnoreCase("temp")) & (!next.getType.equalsIgnoreCase("temp"))) {
            activityAccumulator.append(curr)
          }
          updatedPreviousActivity = curr
        }
      case _ =>
    }
    activityAccumulator.foreach { x =>
      newPlan.addActivity(x)
    }
    if (!elements(elements.size - 2).getType.equalsIgnoreCase("temp")) { newPlan.addActivity(elements.last) }

    if (anyChanges) {
      //newPlan.setScore(plan.getScore)
      newPlan.setType(plan.getType)
      val resultPlan = ReplanningUtil.addNoModeBeamTripsToPlanWithOnlyActivities(newPlan)
      AttributesUtils.copyAttributesFromTo(plan, resultPlan)
      Some(resultPlan)
    } else {
      None
    }
  }

  private def generateSubtour(
    prevActivity: Activity,
    currentActivity: Activity,
    nextActivity: Activity,
    modeMNL: MultinomialLogit[SupplementaryTripAlternative, DestinationParameters],
    destinationMNL: MultinomialLogit[SupplementaryTripAlternative, TripParameters],
    tripMNL: MultinomialLogit[Boolean, TripParameters],
    householdModes: List[BeamMode] = List[BeamMode](CAR)
  ): List[Activity] = {
    val tazChoiceSet: List[TAZ] =
      generateTazChoiceSet(
        beamServices.beamConfig.beam.agentsim.agents.tripBehaviors.mulitnomialLogit.max_destination_choice_set_size,
        prevActivity.getCoord
      )

    val modesToConsider: List[BeamMode] =
      if (householdModes.contains(CAV)) {
        List[BeamMode](CAV, WALK)
      } else {
        List[BeamMode](WALK, WALK_TRANSIT, RIDE_HAIL, RIDE_HAIL_POOLED) ++ householdModes
      }
    val alternativeActivity = PopulationUtils.createActivityFromCoord(prevActivity.getType, currentActivity.getCoord)
    alternativeActivity.setStartTime(prevActivity.getStartTime)
    alternativeActivity.setEndTime(nextActivity.getEndTime)
    val (newActivityType, startTime, endTime) = generateSubtourTypeStartAndEndTime(alternativeActivity)
    val chosenAlternativeOption = newActivityType match {
      case "None" => None
      case _ =>
        val (
          modeTazCosts: Map[SupplementaryTripAlternative, Map[SupplementaryTripAlternative, Map[
            DestinationParameters,
            Double
          ]]],
          noTrip: Map[TripParameters, Double]
        ) =
          gatherSubtourCosts(newActivityType, tazChoiceSet, startTime, endTime, alternativeActivity, modesToConsider)

        val modeChoice: Map[SupplementaryTripAlternative, Map[TripParameters, Double]] =
          modeTazCosts.map {
            case (alt, modeCost) =>
              val tazMaxUtility = modeMNL.getExpectedMaximumUtility(modeCost)
              alt -> Map[TripParameters, Double](
                TripParameters.ExpMaxUtility -> tazMaxUtility.getOrElse(0)
              )
          }

        val tripMaxUtility = destinationMNL.getExpectedMaximumUtility(modeChoice)

        val tripChoice: Map[Boolean, Map[TripParameters, Double]] =
          Map[Boolean, Map[TripParameters, Double]](
            true -> Map[TripParameters, Double](
              TripParameters.ExpMaxUtility -> tripMaxUtility.getOrElse(0)
            ),
            false -> noTrip,
          )

        tripMNL.sampleAlternative(tripChoice, r) match {
          case Some(mnlSample) if mnlSample.alternativeType => destinationMNL.sampleAlternative(modeChoice, r)
          case _                                            => None
        }
    }

    chosenAlternativeOption match {
      case Some(outcome) =>
        val chosenAlternative = outcome.alternativeType

        val newActivity =
          PopulationUtils.createActivityFromCoord(
            newActivityType,
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
  }

  private def gatherSubtourCosts(
    newActivityType: String,
    TAZs: List[TAZ],
    startTime: Int,
    endTime: Int,
    alternativeActivity: Activity,
    modes: List[BeamMode]
  ): (
    Map[SupplementaryTripAlternative, Map[SupplementaryTripAlternative, Map[DestinationParameters, Double]]],
    Map[TripParameters, Double]
  ) = {
    val alternativeActivityUtility =
      destinationChoiceModel.getActivityUtility(alternativeActivity, attributesOfIndividual)
    val alternativeActivityParamMap = Map[DestinationChoiceModel.TripParameters, Double](
      ExpMaxUtility -> alternativeActivityUtility
    )

    val modeToTazToCost
      : Map[SupplementaryTripAlternative, Map[SupplementaryTripAlternative, Map[DestinationParameters, Double]]] =
      if (TAZs.isEmpty) {
        Map[SupplementaryTripAlternative, Map[SupplementaryTripAlternative, Map[DestinationParameters, Double]]]()
      } else {
        TAZs.map { taz =>
          val destinationCoord: Coord = TAZTreeMap.randomLocationInTAZ(taz)
          val additionalActivity = PopulationUtils.createActivityFromCoord(newActivityType, destinationCoord)
          additionalActivity.setStartTime(startTime)
          additionalActivity.setEndTime(endTime)
          val cost =
            getTazCost(additionalActivity, alternativeActivity, modes)
          val alternative =
            DestinationChoiceModel.SupplementaryTripAlternative(
              taz,
              newActivityType,
              CAR,
              endTime - startTime,
              startTime
            )
          alternative -> cost.map {
            case (x, y) =>
              DestinationChoiceModel.SupplementaryTripAlternative(
                taz,
                newActivityType,
                x,
                endTime - startTime,
                startTime
              ) -> DestinationChoiceModel.toUtilityParameters(y)
          }
        }.toMap
      }
    (modeToTazToCost, alternativeActivityParamMap)
  }

  private def getRealStartEndTime(
    activity: Activity
  ): (Double, Double) = {
    val start = if (activity.getStartTime > 0) { activity.getStartTime } else { 0 }
    val end = if (activity.getEndTime > 0) { activity.getEndTime } else { 3600 * 24 }
    (start, end)
  }

  private def getTazCost(
    additionalActivity: Activity,
    alternativeActivity: Activity,
    modes: List[BeamMode]
  ): Map[BeamMode, DestinationChoiceModel.TimesAndCost] = {
    val (altStart, altEnd) = getRealStartEndTime(alternativeActivity)
    val alternativeActivityDuration = altEnd - altStart
    val activityDuration = additionalActivity.getEndTime - additionalActivity.getStartTime
    val desiredDepartTimeBin = secondsToIndex(additionalActivity.getStartTime)
    val desiredReturnTimeBin = secondsToIndex(additionalActivity.getEndTime)

    val modeToTimeAndCosts: Map[BeamMode, DestinationChoiceModel.TimesAndCost] =
      modes.map { mode =>
        val accessTripSkim =
          beamServices.skims.od_skimmer.getTimeDistanceAndCost(
            alternativeActivity.getCoord,
            additionalActivity.getCoord,
            desiredDepartTimeBin,
            mode,
            beamServices.beamScenario.vehicleTypes.keys.head, // TODO: FIX WITH REAL VEHICLE
            beamServices.beamScenario
          )
        val egressTripSkim =
          beamServices.skims.od_skimmer.getTimeDistanceAndCost(
            additionalActivity.getCoord,
            alternativeActivity.getCoord,
            desiredReturnTimeBin,
            mode,
            beamServices.beamScenario.vehicleTypes.keys.head, // TODO: FIX
            beamServices.beamScenario
          )
        val startingOverlap =
          (altStart - (additionalActivity.getStartTime - accessTripSkim.time)).max(0)
        val endingOverlap =
          ((additionalActivity.getEndTime + egressTripSkim.time) - altEnd).max(0)
        val schedulePenalty = math.pow(startingOverlap, 2) + math.pow(endingOverlap, 2)
        val previousActivityBenefit = attributesOfIndividual.getVOT(
          (alternativeActivityDuration - accessTripSkim.time - egressTripSkim.time - activityDuration) / 3600 * activityVOTs
            .getOrElse(alternativeActivity.getType, 1.0)
        )

        val newActivityBenefit: Double =
          destinationChoiceModel.getActivityUtility(additionalActivity, attributesOfIndividual)

        mode -> TimesAndCost(
          accessTripSkim.time,
          egressTripSkim.time,
          attributesOfIndividual.getVOT(accessTripSkim.generalizedTime / 3600) + accessTripSkim.cost,
          attributesOfIndividual.getVOT(egressTripSkim.generalizedTime / 3600) + egressTripSkim.cost,
          schedulePenalty,
          newActivityBenefit + previousActivityBenefit
        )
      }.toMap
    modeToTimeAndCosts
  }

  private def generateSubtourTypeStartAndEndTime(
    alternativeActivity: Activity
  ): (String, Int, Int) = {

    val (altStart, altEnd) = getRealStartEndTime(alternativeActivity)

    val filtered = activityRates.map {
      case (activityType, hourToRate) =>
        activityType -> hourToRate
          .filter {
            case (hour, rate) =>
              hour > secondsToIndex(altStart) & hour <= secondsToIndex(altEnd) & rate > 0
          }
          .values
          .sum
    }
    val chosenType = drawKeyByValue(filtered)

    chosenType match {
      case Some(actType) =>
        val meanActivityDuration: Double = activityDurations.getOrElse(actType, 15 * 60)

        val r_repeat = new scala.util.Random
        r_repeat.setSeed(personSpecificSeed)

        val newActivityDuration: Double = -math.log(r_repeat.nextDouble()) * meanActivityDuration

        val earliestPossibleStartIndex = secondsToIndex(altStart + travelTimeBufferInSec)
        val latestPossibleEndIndex = secondsToIndex(altEnd - travelTimeBufferInSec)
        val chosenStartIndex = if (latestPossibleEndIndex > earliestPossibleStartIndex + 1) {
          val filteredRates = activityRates
            .getOrElse(actType, Map[Int, Double]())
            .filter {
              case (hour, rate) =>
                hour > secondsToIndex(altStart) & hour < secondsToIndex(altEnd - travelTimeBufferInSec) & rate > 0
            }
          drawKeyByValue(filteredRates)
        } else { None }
        chosenStartIndex match {
          case Some(index) =>
            val startTime = math.max((r.nextDouble() + index) * 3600, altStart + travelTimeBufferInSec)
            (
              actType,
              startTime.toInt,
              (startTime + newActivityDuration).toInt
            )
          case None => ("None", 0, 0)
        }
      case None => ("None", 0, 0)
    }
  }

  private def generateTazChoiceSet(n: Int, coord: Coord): List[TAZ] = {
    val maxDistance =
      beamServices.beamConfig.beam.agentsim.agents.tripBehaviors.mulitnomialLogit.max_destination_distance_meters
    val r_repeat = new scala.util.Random
    r_repeat.setSeed(personSpecificSeed)
    r_repeat
      .shuffle(
        beamServices.beamScenario.tazTreeMap.getTAZInRadius(coord, maxDistance).asScala.toSeq.sortBy(_.tazId.toString)
      )
      .take(n)
      .toList
  }

  private def secondsToIndex(time: Double): Int = {
    (time / 3600).toInt
  }

  private def drawKeyByValue[A](
    keyToProb: Map[A, Double]
  ): Option[A] = {
    val totalProb = keyToProb.values.sum
    val randomDraw = r.nextDouble()
    val probs = keyToProb.values.scanLeft(0.0)(_ + _ / totalProb).drop(1)
    keyToProb.keys.zip(probs).dropWhile { _._2 <= randomDraw }.headOption match {
      case Some(result) => Some(result._1)
      case _            => None
    }
  }

}
