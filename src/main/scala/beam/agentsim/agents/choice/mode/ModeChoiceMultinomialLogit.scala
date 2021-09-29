package beam.agentsim.agents.choice.mode

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import beam.agentsim.agents.choice.logit
import beam.agentsim.agents.choice.logit._
import beam.agentsim.agents.choice.mode.ModeChoiceMultinomialLogit.{ModeCostTimeTransfer, _}
import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator
import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator._
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.events.ModeChoiceOccurredEvent
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode._
import beam.router.model.{BeamPath, EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.router.r5.BikeLanesAdjustment
import beam.router.skim.readonly.TransitCrowdingSkims
import beam.sim.BeamServices
import beam.sim.config.{BeamConfig, BeamConfigHolder}
import beam.sim.config.BeamConfig.Beam.Agentsim.Agents.ModalBehaviors
import beam.sim.population.AttributesOfIndividual
import beam.utils.logging.ExponentialLazyLogging
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.{Activity, Person}
import org.matsim.core.api.experimental.events.EventsManager

/**
  * BEAM
  */
class ModeChoiceMultinomialLogit(
  val beamServices: BeamServices,
  val model: MultinomialLogit[EmbodiedBeamTrip, String],
  val modeModel: MultinomialLogit[BeamMode, String],
  beamConfigHolder: BeamConfigHolder,
  transitCrowding: TransitCrowdingSkims,
  val eventsManager: EventsManager
) extends ModeChoiceCalculator
    with ExponentialLazyLogging {

  override lazy val beamConfig: BeamConfig = beamConfigHolder.beamConfig

  var expectedMaximumUtility: Double = 0.0
  val modalBehaviors: ModalBehaviors = beamConfig.beam.agentsim.agents.modalBehaviors

  private val shouldLogDetails: Boolean = false
  private val bikeLanesAdjustment: BikeLanesAdjustment = beamServices.bikeLanesAdjustment

  override def apply(
    alternatives: IndexedSeq[TripDataOrTrip],
    attributesOfIndividual: AttributesOfIndividual,
    destinationActivity: Option[Activity],
    person: Option[Person] = None
  ): Option[TripDataOrTrip] = {
    if (alternatives.isEmpty) {
      None
    } else {
      val modeCostTimeTransfers = altsToModeCostTimeTransfers(alternatives, attributesOfIndividual, destinationActivity)

      val bestInGroup = modeCostTimeTransfers groupBy (mct => extractTripClassifier(mct.beamTripData)) mapValues {
        group =>
          findBestIn(group)
      }
      val inputData = bestInGroup.mapValues { mct =>
        val theParams: Map[String, Double] =
          Map("cost" -> timeAndCost(mct))
        val transferParam: Map[String, Double] = if (extractTripClassifier(mct.beamTripData).isTransit) {
          Map("transfer" -> mct.numTransfers, "transitOccupancyLevel" -> mct.transitOccupancyLevel)
        } else {
          Map()
        }
        theParams ++ transferParam
      }

      val alternativesWithUtility = modeModel.calcAlternativesWithUtility(inputData)
      val chosenModeOpt = modeModel.sampleAlternative(alternativesWithUtility, random)

      expectedMaximumUtility = modeModel.getExpectedMaximumUtility(inputData).getOrElse(0)

      if (shouldLogDetails) {
        val personId = person.map(_.getId)
        val msgToLog =
          s"""|@@@[$personId]-----------------------------------------
              |@@@[$personId]Alternatives:$alternatives
              |@@@[$personId]AttributesOfIndividual:$attributesOfIndividual
              |@@@[$personId]DestinationActivity:$destinationActivity
              |@@@[$personId]modeCostTimeTransfers:$modeCostTimeTransfers
              |@@@[$personId]bestInGroup:$bestInGroup
              |@@@[$personId]inputData:$inputData
              |@@@[$personId]chosenModeOpt:$chosenModeOpt
              |@@@[$personId]expectedMaximumUtility:$expectedMaximumUtility
              |@@@[$personId]-----------------------------------------
              |""".stripMargin
        logger.debug(msgToLog)
      }

      chosenModeOpt match {
        case Some(chosenMode) =>
          val chosenModeCostTime = bestInGroup(chosenMode.alternativeType)
          if (chosenModeCostTime.index < 0) {
            None
          } else {
            if (beamServices.beamConfig.beam.debug.writeModeChoiceAlternatives)
              createModeChoiceOccurredEvent(
                person,
                alternativesWithUtility,
                modeCostTimeTransfers,
                alternatives,
                chosenModeCostTime
              ).foreach(eventsManager.processEvent)

            Some(alternatives(chosenModeCostTime.index))
          }
        case None =>
          None
      }
    }
  }

  private def findBestIn(group: IndexedSeq[ModeCostTimeTransfer]): ModeCostTimeTransfer = {
    if (group.size == 1) {
      group.head
    } else if (extractTripClassifier(group.head.beamTripData).isTransit) {
      //all elements in the group are either EmbodiedBeamTrip or TripChoiceData
      //we're building inputData in case all elements are EmbodiedBeamTrip
      //otherwise inputData is empty and we choose min by time and cost
      val inputData: Map[EmbodiedBeamTrip, Map[String, Double]] = group.collect {
        case mct @ ModeCostTimeTransfer(Right(trip), _, _, _, _, _) =>
          trip -> attributes(timeAndCost(mct), mct.transitOccupancyLevel, mct.numTransfers)
      }.toMap
      val alternativesWithUtility = model.calcAlternativesWithUtility(inputData)
      val chosenModeOpt = model.sampleAlternative(alternativesWithUtility, random)
      chosenModeOpt
        .flatMap(sample => group.find(mct => mct.beamTripData.contains(sample.alternativeType)))
        .getOrElse(group minBy timeAndCost)
    } else {
      group minBy timeAndCost
    }
  }

  private def createModeChoiceOccurredEvent(
    person: Option[Person],
    alternativesWithUtility: Iterable[MultinomialLogit.AlternativeWithUtility[BeamMode]],
    modeCostTimeTransfers: IndexedSeq[ModeCostTimeTransfer],
    alternatives: IndexedSeq[TripDataOrTrip],
    chosenModeCostTime: ModeCostTimeTransfer
  ): Option[ModeChoiceOccurredEvent] = {
    person match {
      case Some(p) =>
        val altUtility = alternativesWithUtility
          .map(au =>
            au.alternative.value.toLowerCase() -> ModeChoiceOccurredEvent
              .AltUtility(au.utility, au.expUtility)
          )
          .toMap

        val altCostTimeTransfer = modeCostTimeTransfers
          .map(mctt =>
            extractTripClassifier(mctt.beamTripData).value.toLowerCase() -> ModeChoiceOccurredEvent
              .AltCostTimeTransfer(mctt.cost, mctt.scaledTime, mctt.numTransfers)
          )
          .toMap

        val time = alternatives.collectFirst { case Right(trip) =>
          trip.legs.headOption map { leg =>
            leg.beamLeg.startTime
          }
        }.flatten

        if (time.nonEmpty) {
          Some(
            ModeChoiceOccurredEvent(
              time.get,
              p.getId.toString,
              alternatives,
              altCostTimeTransfer,
              altUtility,
              chosenModeCostTime.index
            )
          )
        } else {
          None
        }

      case _ => None
    }
  }

  private def timeAndCost(mct: ModeCostTimeTransfer): Double = {
    mct.scaledTime + mct.cost
  }

  // Generalized Time is always in hours!

  override def getGeneralizedTimeOfTrip(
    embodiedBeamTrip: EmbodiedBeamTrip,
    attributesOfIndividual: Option[AttributesOfIndividual],
    destinationActivity: Option[Activity]
  ): Double = {
    getGeneralizedTimeOfTripInHours(embodiedBeamTrip, attributesOfIndividual, destinationActivity)
  }

  private def getGeneralizedTimeOfTripInHours(
    embodiedBeamTrip: EmbodiedBeamTrip,
    attributesOfIndividual: Option[AttributesOfIndividual],
    destinationActivity: Option[Activity],
    adjustSpecialBikeLines: Boolean = false
  ): Double = {
    val adjustedTripDuration = if (adjustSpecialBikeLines && embodiedBeamTrip.tripClassifier == BIKE) {
      calculateBeamTripTimeInSecsWithSpecialBikeLanesAdjustment(embodiedBeamTrip, bikeLanesAdjustment)
    } else {
      embodiedBeamTrip.legs.map(_.beamLeg.duration).sum
    }
    val waitingTime: Int = embodiedBeamTrip.totalTravelTimeInSecs - adjustedTripDuration
    embodiedBeamTrip.legs.map { x: EmbodiedBeamLeg =>
      val factor = if (adjustSpecialBikeLines) {
        bikeLanesAdjustment.scaleFactor(x.beamLeg.mode, adjustSpecialBikeLines)
      } else {
        1d
      }
      getGeneralizedTimeOfLeg(x, attributesOfIndividual, destinationActivity) * factor
    }.sum + getGeneralizedTime(waitingTime, None, None)
  }

  override def getGeneralizedTimeOfLeg(
    embodiedBeamLeg: EmbodiedBeamLeg,
    attributesOfIndividual: Option[AttributesOfIndividual],
    destinationActivity: Option[Activity]
  ): Double = {
    attributesOfIndividual match {
      case Some(attributes) =>
        attributes.getGeneralizedTimeOfLegForMNL(
          embodiedBeamLeg,
          this,
          beamServices,
          destinationActivity
        )
      case None =>
        embodiedBeamLeg.beamLeg.duration * modeMultipliers.getOrElse(Some(embodiedBeamLeg.beamLeg.mode), 1.0) / 3600
    }
  }

  override def getGeneralizedTime(
    time: Double,
    beamMode: Option[BeamMode] = None,
    beamLeg: Option[EmbodiedBeamLeg] = None
  ): Double = {
    time / 3600 * modeMultipliers.getOrElse(beamMode, 1.0)
  }

  private def altsToModeCostTimeTransfers(
    alternatives: IndexedSeq[TripDataOrTrip],
    attributesOfIndividual: AttributesOfIndividual,
    destinationActivity: Option[Activity]
  ): IndexedSeq[ModeCostTimeTransfer] = {
    alternatives.zipWithIndex.map { case (alt, idx) =>
      val mode: BeamMode = extractTripClassifier(alt)

      val numTransfers = (alt, mode) match {
        case (Right(trip), TRANSIT | WALK_TRANSIT | DRIVE_TRANSIT | RIDE_HAIL_TRANSIT | BIKE_TRANSIT) =>
          var nVeh = -1
          var vehId = Id.create("dummy", classOf[BeamVehicle])
          trip.legs.foreach { leg =>
            if (leg.beamLeg.mode.isTransit && leg.beamVehicleId != vehId) {
              vehId = leg.beamVehicleId
              nVeh = nVeh + 1
            }
          }
          nVeh
        case _ =>
          0
      }
      assert(numTransfers >= 0)

      val (totalCost, scaledTime, occupancyLevel) = alt match {
        case Left(data) => (data.cost, data.time, 0.0)
        case Right(trip) =>
          val percentile =
            beamConfig.beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.transit_crowding_percentile
          (
            getNonTimeCost(trip, includeReplanningPenalty = true),
            attributesOfIndividual.getVOT(
              getGeneralizedTimeOfTripInHours(
                trip,
                Some(attributesOfIndividual),
                destinationActivity,
                adjustSpecialBikeLines = true
              )
            ),
            transitCrowding.getTransitOccupancyLevelForPercentile(trip, percentile)
          )
      }
      val incentive: Double = beamServices.beamScenario.modeIncentives.computeIncentive(attributesOfIndividual, mode)

      val incentivizedCost = Math.max(0, totalCost - incentive)

      if (totalCost < incentive)
        logger.warn(
          "Mode incentive is even higher then the cost, setting cost to zero. Mode: {}, Cost: {}, Incentive: {}",
          mode,
          totalCost,
          incentive
        )

      ModeCostTimeTransfer(
        beamTripData = alt,
        cost = incentivizedCost,
        scaledTime = scaledTime,
        numTransfers = numTransfers,
        transitOccupancyLevel = occupancyLevel,
        index = idx
      )
    }
  }

  lazy val modeMultipliers: mutable.Map[Option[BeamMode], Double] =
    mutable.Map[Option[BeamMode], Double](
      Some(TRANSIT)           -> modalBehaviors.modeVotMultiplier.transit,
      Some(RIDE_HAIL)         -> modalBehaviors.modeVotMultiplier.rideHail,
      Some(RIDE_HAIL_POOLED)  -> modalBehaviors.modeVotMultiplier.rideHailPooled,
      Some(RIDE_HAIL_TRANSIT) -> modalBehaviors.modeVotMultiplier.rideHailTransit,
      Some(CAV)               -> modalBehaviors.modeVotMultiplier.CAV,
//      Some(WAITING)          -> modalBehaviors.modeVotMultiplier.waiting, TODO think of alternative for waiting. For now assume "NONE" is waiting
      Some(BIKE) -> modalBehaviors.modeVotMultiplier.bike,
      Some(WALK) -> modalBehaviors.modeVotMultiplier.walk,
      Some(CAR)  -> modalBehaviors.modeVotMultiplier.drive,
      None       -> modalBehaviors.modeVotMultiplier.waiting
    )

  lazy val poolingMultipliers: mutable.Map[automationLevel, Double] =
    mutable.Map[automationLevel, Double](
      levelLE2 -> modalBehaviors.poolingMultiplier.LevelLE2,
      level3   -> modalBehaviors.poolingMultiplier.Level3,
      level4   -> modalBehaviors.poolingMultiplier.Level4,
      level5   -> modalBehaviors.poolingMultiplier.Level5
    )

  lazy val situationMultipliers: mutable.Map[(timeSensitivity, congestionLevel, roadwayType, automationLevel), Double] =
    mutable.Map[(timeSensitivity, congestionLevel, roadwayType, automationLevel), Double](
      (
        highSensitivity,
        highCongestion,
        highway,
        levelLE2
      ) -> modalBehaviors.highTimeSensitivity.highCongestion.highwayFactor.LevelLE2,
      (
        highSensitivity,
        highCongestion,
        nonHighway,
        levelLE2
      ) -> modalBehaviors.highTimeSensitivity.highCongestion.nonHighwayFactor.LevelLE2,
      (
        highSensitivity,
        lowCongestion,
        highway,
        levelLE2
      ) -> modalBehaviors.highTimeSensitivity.lowCongestion.highwayFactor.LevelLE2,
      (
        highSensitivity,
        lowCongestion,
        nonHighway,
        levelLE2
      ) -> modalBehaviors.highTimeSensitivity.lowCongestion.nonHighwayFactor.LevelLE2,
      (
        lowSensitivity,
        highCongestion,
        highway,
        levelLE2
      ) -> modalBehaviors.lowTimeSensitivity.highCongestion.highwayFactor.LevelLE2,
      (
        lowSensitivity,
        highCongestion,
        nonHighway,
        levelLE2
      ) -> modalBehaviors.lowTimeSensitivity.highCongestion.nonHighwayFactor.LevelLE2,
      (
        lowSensitivity,
        lowCongestion,
        highway,
        levelLE2
      ) -> modalBehaviors.lowTimeSensitivity.lowCongestion.highwayFactor.LevelLE2,
      (
        lowSensitivity,
        lowCongestion,
        nonHighway,
        levelLE2
      ) -> modalBehaviors.lowTimeSensitivity.lowCongestion.nonHighwayFactor.LevelLE2,
      (
        highSensitivity,
        highCongestion,
        highway,
        level3
      ) -> modalBehaviors.highTimeSensitivity.highCongestion.highwayFactor.Level3,
      (
        highSensitivity,
        highCongestion,
        nonHighway,
        level3
      ) -> modalBehaviors.highTimeSensitivity.highCongestion.nonHighwayFactor.Level3,
      (
        highSensitivity,
        lowCongestion,
        highway,
        level3
      ) -> modalBehaviors.highTimeSensitivity.lowCongestion.highwayFactor.Level3,
      (
        highSensitivity,
        lowCongestion,
        nonHighway,
        level3
      ) -> modalBehaviors.highTimeSensitivity.lowCongestion.nonHighwayFactor.Level3,
      (
        lowSensitivity,
        highCongestion,
        highway,
        level3
      ) -> modalBehaviors.lowTimeSensitivity.highCongestion.highwayFactor.Level3,
      (
        lowSensitivity,
        highCongestion,
        nonHighway,
        level3
      ) -> modalBehaviors.lowTimeSensitivity.highCongestion.nonHighwayFactor.Level3,
      (
        lowSensitivity,
        lowCongestion,
        highway,
        level3
      ) -> modalBehaviors.lowTimeSensitivity.lowCongestion.highwayFactor.Level3,
      (
        lowSensitivity,
        lowCongestion,
        nonHighway,
        level3
      ) -> modalBehaviors.lowTimeSensitivity.lowCongestion.nonHighwayFactor.Level3,
      (
        highSensitivity,
        highCongestion,
        highway,
        level4
      ) -> modalBehaviors.highTimeSensitivity.highCongestion.highwayFactor.Level4,
      (
        highSensitivity,
        highCongestion,
        nonHighway,
        level4
      ) -> modalBehaviors.highTimeSensitivity.highCongestion.nonHighwayFactor.Level4,
      (
        highSensitivity,
        lowCongestion,
        highway,
        level4
      ) -> modalBehaviors.highTimeSensitivity.lowCongestion.highwayFactor.Level4,
      (
        highSensitivity,
        lowCongestion,
        nonHighway,
        level4
      ) -> modalBehaviors.highTimeSensitivity.lowCongestion.nonHighwayFactor.Level4,
      (
        lowSensitivity,
        highCongestion,
        highway,
        level4
      ) -> modalBehaviors.lowTimeSensitivity.highCongestion.highwayFactor.Level4,
      (
        lowSensitivity,
        highCongestion,
        nonHighway,
        level4
      ) -> modalBehaviors.lowTimeSensitivity.highCongestion.nonHighwayFactor.Level4,
      (
        lowSensitivity,
        lowCongestion,
        highway,
        level4
      ) -> modalBehaviors.lowTimeSensitivity.lowCongestion.highwayFactor.Level4,
      (
        lowSensitivity,
        lowCongestion,
        nonHighway,
        level4
      ) -> modalBehaviors.lowTimeSensitivity.lowCongestion.nonHighwayFactor.Level4,
      (
        highSensitivity,
        highCongestion,
        highway,
        level5
      ) -> modalBehaviors.highTimeSensitivity.highCongestion.highwayFactor.Level5,
      (
        highSensitivity,
        highCongestion,
        nonHighway,
        level5
      ) -> modalBehaviors.highTimeSensitivity.highCongestion.nonHighwayFactor.Level5,
      (
        highSensitivity,
        lowCongestion,
        highway,
        level5
      ) -> modalBehaviors.highTimeSensitivity.lowCongestion.highwayFactor.Level5,
      (
        highSensitivity,
        lowCongestion,
        nonHighway,
        level5
      ) -> modalBehaviors.highTimeSensitivity.lowCongestion.nonHighwayFactor.Level5,
      (
        lowSensitivity,
        highCongestion,
        highway,
        level5
      ) -> modalBehaviors.lowTimeSensitivity.highCongestion.highwayFactor.Level5,
      (
        lowSensitivity,
        highCongestion,
        nonHighway,
        level5
      ) -> modalBehaviors.lowTimeSensitivity.highCongestion.nonHighwayFactor.Level5,
      (
        lowSensitivity,
        lowCongestion,
        highway,
        level5
      ) -> modalBehaviors.lowTimeSensitivity.lowCongestion.highwayFactor.Level5,
      (
        lowSensitivity,
        lowCongestion,
        nonHighway,
        level5
      ) -> modalBehaviors.lowTimeSensitivity.lowCongestion.nonHighwayFactor.Level5
    )

  override def utilityOf(
    alternative: TripDataOrTrip,
    attributesOfIndividual: AttributesOfIndividual,
    destinationActivity: Option[Activity]
  ): Double = {
    val modeCostTimeTransfer =
      altsToModeCostTimeTransfers(IndexedSeq(alternative), attributesOfIndividual, destinationActivity).head
    utilityOf(modeCostTimeTransfer)
  }

  private def utilityOf(mct: ModeCostTimeTransfer): Double = {
    modeModel
      .getUtilityOfAlternative(
        extractTripClassifier(mct.beamTripData),
        attributes(mct.cost, mct.transitOccupancyLevel, mct.numTransfers)
      )
      .getOrElse(0)
  }

  override def utilityOf(
    mode: BeamMode,
    cost: Double,
    time: Double,
    numTransfers: Int = 0,
    transitOccupancyLevel: Double
  ): Double = {
    modeModel.getUtilityOfAlternative(mode, attributes(cost, transitOccupancyLevel, numTransfers)).getOrElse(0)
  }

  private def attributes(cost: Double, transitOccupancyLevel: Double, numTransfers: Int) = {
    Map(
      "transfer"              -> numTransfers.toDouble,
      "cost"                  -> cost,
      "transitOccupancyLevel" -> transitOccupancyLevel
    )
  }

  override def computeAllDayUtility(
    trips: ListBuffer[TripDataOrTrip],
    person: Person,
    attributesOfIndividual: AttributesOfIndividual
  ): Double = trips.map(utilityOf(_, attributesOfIndividual, None)).sum // TODO: Update with destination activity
}

object ModeChoiceMultinomialLogit {

  def buildModelFromConfig(
    configHolder: BeamConfigHolder
  ): (MultinomialLogit[EmbodiedBeamTrip, String], MultinomialLogit[BeamMode, String]) = {

    val params = configHolder.beamConfig.beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params
    val commonUtility: Map[String, UtilityFunctionOperation] = Map(
      "cost" -> UtilityFunctionOperation("multiplier", -1)
    )
    val scale_factor: Double =
      configHolder.beamConfig.beam.agentsim.agents.modalBehaviors.mulitnomialLogit.utility_scale_factor
    val mnlUtilityFunctions: Map[String, Map[String, UtilityFunctionOperation]] = Map(
      "car" -> Map(
        "intercept" ->
        UtilityFunctionOperation("intercept", params.car_intercept)
      ),
      "cav"       -> Map("intercept" -> UtilityFunctionOperation("intercept", params.cav_intercept)),
      "walk"      -> Map("intercept" -> UtilityFunctionOperation("intercept", params.walk_intercept)),
      "ride_hail" -> Map("intercept" -> UtilityFunctionOperation("intercept", params.ride_hail_intercept)),
      "ride_hail_pooled" -> Map(
        "intercept" -> UtilityFunctionOperation("intercept", params.ride_hail_pooled_intercept)
      ),
      "ride_hail_transit" -> Map(
        "intercept"             -> UtilityFunctionOperation("intercept", params.ride_hail_transit_intercept),
        "transitOccupancyLevel" -> UtilityFunctionOperation("multiplier", params.transit_crowding),
        "transfer"              -> UtilityFunctionOperation("multiplier", params.transfer)
      ),
      "bike" -> Map("intercept" -> UtilityFunctionOperation("intercept", params.bike_intercept)),
      "walk_transit" -> Map(
        "intercept"             -> UtilityFunctionOperation("intercept", params.walk_transit_intercept),
        "transitOccupancyLevel" -> UtilityFunctionOperation("multiplier", params.transit_crowding),
        "transfer"              -> UtilityFunctionOperation("multiplier", params.transfer)
      ),
      "drive_transit" -> Map(
        "intercept"             -> UtilityFunctionOperation("intercept", params.drive_transit_intercept),
        "transitOccupancyLevel" -> UtilityFunctionOperation("multiplier", params.transit_crowding),
        "transfer"              -> UtilityFunctionOperation("multiplier", params.transfer)
      ),
      "bike_transit" -> Map(
        "intercept"             -> UtilityFunctionOperation("intercept", params.bike_transit_intercept),
        "transitOccupancyLevel" -> UtilityFunctionOperation("multiplier", params.transit_crowding),
        "transfer"              -> UtilityFunctionOperation("multiplier", params.transfer)
      )
    )

    (
      new logit.MultinomialLogit(
        trip => mnlUtilityFunctions.get(trip.tripClassifier.value),
        commonUtility,
        scale_factor
      ),
      new logit.MultinomialLogit(
        mode => mnlUtilityFunctions.get(mode.value),
        commonUtility,
        scale_factor
      )
    )
  }

  case class ModeCostTimeTransfer(
    beamTripData: TripDataOrTrip,
    cost: Double,
    scaledTime: Double,
    numTransfers: Int,
    transitOccupancyLevel: Double,
    index: Int = -1
  )

  def calculateBeamTripTimeInSecsWithSpecialBikeLanesAdjustment(
    embodiedBeamTrip: EmbodiedBeamTrip,
    bikeLanesAdjustment: BikeLanesAdjustment
  ): Int = {
    embodiedBeamTrip.legs
      .map { embodiedBeamLeg: EmbodiedBeamLeg =>
        pathScaledForWaiting(embodiedBeamLeg.beamLeg.travelPath, bikeLanesAdjustment)
      }
      .sum
      .toInt
  }

  private[mode] def pathScaledForWaiting(path: BeamPath, bikeLanesAdjustment: BikeLanesAdjustment): Double = {
    path.linkIds
      .drop(1)
      .zip(path.linkTravelTime.drop(1))
      .map { case (linkId: Int, travelTime: Double) =>
        travelTime * 1d / bikeLanesAdjustment.scaleFactor(linkId)
      }
      .sum
  }

}
