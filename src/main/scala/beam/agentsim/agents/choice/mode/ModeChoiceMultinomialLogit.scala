package beam.agentsim.agents.choice.mode

import beam.agentsim.agents.choice.logit
import beam.agentsim.agents.choice.logit._
import beam.agentsim.agents.choice.mode.ModeChoiceMultinomialLogit.{
  calculateBeamTripTimeInSecsWithSpecialBikeLanesAdjustment,
  ModeCostTimeTransfer
}
import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator
import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator._
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
import beam.agentsim.events.ModeChoiceOccurredEvent
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode._
import beam.router.model.{BeamPath, EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.router.r5.BikeLanesAdjustment
import beam.router.skim.readonly.TransitCrowdingSkims
import beam.sim.BeamServices
import beam.sim.config.BeamConfig.Beam.Agentsim.Agents.ModalBehaviors
import beam.sim.config.{BeamConfig, BeamConfigHolder}
import beam.sim.population.AttributesOfIndividual
import beam.utils.logging.ExponentialLazyLogging
import com.typesafe.scalalogging.StrictLogging
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.{Activity, Person}
import org.matsim.core.api.experimental.events.EventsManager

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

/**
  * BEAM
  */
class ModeChoiceMultinomialLogit(
  val beamServices: BeamServices,
  val model: MultinomialLogit[EmbodiedBeamTrip, String],
  val modeModel: MultinomialLogit[BeamMode, String],
  val transitVehicleTypeVOTMultipliers: Map[Id[BeamVehicleType], Double],
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
    alternatives: IndexedSeq[EmbodiedBeamTrip],
    attributesOfIndividual: AttributesOfIndividual,
    destinationActivity: Option[Activity],
    person: Option[Person] = None
  ): Option[EmbodiedBeamTrip] = {
    if (alternatives.isEmpty) {
      None
    } else {
      val modeCostTimeTransfers = altsToModeCostTimeTransfers(alternatives, attributesOfIndividual, destinationActivity)

      val bestInGroup = modeCostTimeTransfers groupBy (_.embodiedBeamTrip.tripClassifier) map { case (_, group) =>
        findBestIn(group)
      }
      val inputData = bestInGroup.map { mct =>
        val theParams: Map[String, Double] =
          Map("cost" -> timeAndCost(mct))
        val transferParam: Map[String, Double] = if (mct.embodiedBeamTrip.tripClassifier.isTransit) {
          Map("transfer" -> mct.numTransfers, "transitOccupancyLevel" -> mct.transitOccupancyLevel)
        } else {
          Map()
        }
        (mct.embodiedBeamTrip, theParams ++ transferParam)
      }.toMap

      val alternativesWithUtility = model.calcAlternativesWithUtility(inputData)
      val chosenModeOpt = model.sampleAlternative(alternativesWithUtility, random)

      expectedMaximumUtility = model.getExpectedMaximumUtility(inputData).getOrElse(0)

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
          val chosenModeCostTime =
            bestInGroup.filter(_.embodiedBeamTrip == chosenMode.alternativeType)
          if (chosenModeCostTime.isEmpty || chosenModeCostTime.head.index < 0) {
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

            Some(alternatives(chosenModeCostTime.head.index))
          }
        case None =>
          None
      }
    }
  }

  private def findBestIn(group: IndexedSeq[ModeCostTimeTransfer]): ModeCostTimeTransfer = {
    if (group.size == 1) {
      group.head
    } else if (group.head.embodiedBeamTrip.tripClassifier.isTransit) {
      val inputData = group
        .map(mct => mct.embodiedBeamTrip -> attributes(timeAndCost(mct), mct.transitOccupancyLevel, mct.numTransfers))
        .toMap
      val alternativesWithUtility = model.calcAlternativesWithUtility(inputData)
      val chosenModeOpt = model.sampleAlternative(alternativesWithUtility, random)
      chosenModeOpt
        .flatMap(sample => group.find(_.embodiedBeamTrip == sample.alternativeType))
        .getOrElse(group minBy timeAndCost)
    } else {
      group minBy timeAndCost
    }
  }

  private def createModeChoiceOccurredEvent(
    person: Option[Person],
    alternativesWithUtility: Iterable[MultinomialLogit.AlternativeWithUtility[EmbodiedBeamTrip]],
    modeCostTimeTransfers: IndexedSeq[ModeCostTimeTransfer],
    alternatives: IndexedSeq[EmbodiedBeamTrip],
    chosenModeCostTime: Iterable[ModeCostTimeTransfer]
  ): Option[ModeChoiceOccurredEvent] = {
    person match {
      case Some(p) =>
        val altUtility = alternativesWithUtility
          .map(au =>
            au.alternative.tripClassifier.value.toLowerCase() -> ModeChoiceOccurredEvent
              .AltUtility(au.utility, au.expUtility)
          )
          .toMap

        val altCostTimeTransfer = modeCostTimeTransfers
          .map(mctt =>
            mctt.embodiedBeamTrip.tripClassifier.value.toLowerCase() -> ModeChoiceOccurredEvent
              .AltCostTimeTransfer(mctt.cost, mctt.scaledTime, mctt.numTransfers)
          )
          .toMap

        val time = alternatives.headOption match {
          case Some(trip) =>
            trip.legs.headOption match {
              case Some(leg) => Some(leg.beamLeg.startTime)
              case None      => None
            }
          case None => None
        }

        if (time.nonEmpty) {
          Some(
            ModeChoiceOccurredEvent(
              time.get,
              p.getId.toString,
              alternatives,
              altCostTimeTransfer,
              altUtility,
              chosenModeCostTime.head.index
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
    }.sum + getGeneralizedTime(waitingTime, None, None) + getGeneralizedOtherTrip(embodiedBeamTrip,attributesOfIndividual, destinationActivity)
  }

  override def getGeneralizedTimeOfLeg(
    embodiedBeamTrip: EmbodiedBeamTrip,
    embodiedBeamLeg: EmbodiedBeamLeg,
    attributesOfIndividual: Option[AttributesOfIndividual],
    destinationActivity: Option[Activity]
  ): Double = {
    attributesOfIndividual match {
      case Some(attributes) =>
        attributes.getGeneralizedTimeOfLegForMNL(
          embodiedBeamTrip,
          embodiedBeamLeg,
          this,
          beamServices,
          destinationActivity,
          Some(transitCrowding)
        )
      case None =>
        embodiedBeamLeg.beamLeg.duration * modeMultipliers.getOrElse(Some(embodiedBeamLeg.beamLeg.mode), 1.0) / 3600
    }
  }

// (JL) NEW METHOD: getGeneralizedOtherTrip gets the time-valued (in hours) utility associated with other individual-specific characteristics (demographics, origin, destination, etc.)

  def getGeneralizedOtherTrip(
    embodiedBeamTrip: EmbodiedBeamTrip,
    attributesOfIndividual: Option[AttributesOfIndividual],
    destinationActivity: Option[Activity]
  ): Double = {
    val otherVals: mutable.Map[String, Double] = attributesOfIndividual match {
      case Some(attributes) =>
        attributes.getGeneralizedOtherOfTripForMNL(embodiedBeamTrip,
          destinationActivity)
      case None =>
        mutable.Map[String,Double](
          "gender" -> 0.0,
          "age_30_to_50"->0.0,
          "age_50_to_70"->0.0,
          "age_70_over"->0.0,
          "income_under_35k"->0.0,
          "income_35_to_100k"->0.0,
          "income_100k_more"->0.0,
          "workTrip"->0.0,
          "linkToTransit"->0.0,
          "car_owner"->0.0
        )
    }
    val otherMults: mutable.Map[String, Double] = otherMultipliers.getOrElse(Some(embodiedBeamTrip.tripClassifier),  mutable.Map[String,Double](
      "gender" -> 0.0,
      "age_30_to_50"->0.0,
      "age_50_to_70"->0.0,
      "age_70_over"->0.0,
      "income_under_35k"->0.0,
      "income_35_to_100k"->0.0,
      "income_100k_more"->0.0,
      "workTrip"->0.0,
      "linkToTransit"->0.0,
      "car_owner"->0.0
    ))
    otherMults.map { case (k, v) => (k, v * otherVals.getOrElse(k, 0.0)) }.foldLeft(0.0)(_+_._2)

  override def getCrowdingForTrip(embodiedBeamTrip: EmbodiedBeamTrip): Double = {
    val percentile =
      beamConfig.beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.transit_crowding_percentile
    transitCrowding.getTransitOccupancyLevelForPercentile(embodiedBeamTrip, percentile)

  }

  override def getGeneralizedTime(
    time: Double,
    beamMode: Option[BeamMode] = None,
    beamLeg: Option[EmbodiedBeamLeg] = None
  ): Double = {
    time / 3600 * modeMultipliers.getOrElse(beamMode, 1.0)
  }

  private def altsToModeCostTimeTransfers(
    alternatives: IndexedSeq[EmbodiedBeamTrip],
    attributesOfIndividual: AttributesOfIndividual,
    destinationActivity: Option[Activity]
  ): IndexedSeq[ModeCostTimeTransfer] = {
    alternatives.zipWithIndex.map { altAndIdx =>
      val mode = altAndIdx._1.tripClassifier
      val totalCost = getNonTimeCost(altAndIdx._1, includeReplanningPenalty = true)
      val incentive: Double = beamServices.beamScenario.modeIncentives.computeIncentive(attributesOfIndividual, mode)

      val incentivizedCost = Math.max(0, totalCost - incentive)

      if (totalCost < incentive)
        logger.warn(
          "Mode incentive is even higher then the cost, setting cost to zero. Mode: {}, Cost: {}, Incentive: {}",
          mode,
          totalCost,
          incentive
        )

      val numTransfers = mode match {
        case TRANSIT | WALK_TRANSIT | DRIVE_TRANSIT | RIDE_HAIL_TRANSIT | BIKE_TRANSIT =>
          var nVeh = -1
          var vehId = Id.create("dummy", classOf[BeamVehicle])
          altAndIdx._1.legs.foreach { leg =>
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
      val scaledTime = attributesOfIndividual.getVOT(
        getGeneralizedTimeOfTripInHours(
          altAndIdx._1,
          Some(attributesOfIndividual),
          destinationActivity,
          adjustSpecialBikeLines = true
        )
      )
      val occupancyLevel = getCrowdingForTrip(altAndIdx._1)

      ModeCostTimeTransfer(
        embodiedBeamTrip = altAndIdx._1,
        cost = incentivizedCost,
        scaledTime = scaledTime,
        numTransfers = numTransfers,
        transitOccupancyLevel = occupancyLevel,
        index = altAndIdx._2
      )
    }
  }

  lazy val modeMultipliers: mutable.Map[Option[BeamMode], Double] =
    mutable.Map[Option[BeamMode], Double](
      // Some(WAITING)        -> modalBehaviors.modeVotMultiplier.waiting, TODO think of alternative for waiting. For now assume "NONE" is waiting
      Some(TRANSIT)           -> modalBehaviors.modeVotMultiplier.transit,
      Some(RIDE_HAIL)         -> modalBehaviors.modeVotMultiplier.rideHail,
      Some(RIDE_HAIL_POOLED)  -> modalBehaviors.modeVotMultiplier.rideHailPooled,
      Some(RIDE_HAIL_TRANSIT) -> modalBehaviors.modeVotMultiplier.rideHailTransit,
      Some(CAV)               -> modalBehaviors.modeVotMultiplier.CAV,
      Some(BIKE)              -> modalBehaviors.modeVotMultiplier.bike,
      Some(WALK)              -> modalBehaviors.modeVotMultiplier.walk,
      Some(CAR)               -> modalBehaviors.modeVotMultiplier.drive,
      None                    -> modalBehaviors.modeVotMultiplier.waiting
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

// NEW VARIABLES for other individual-specific utility parameters
  lazy val incomeMultiplier: Double = modalBehaviors.incomeMultiplier.inVehTime

  lazy val otherMultipliers: mutable.Map[Option[BeamMode], mutable.Map[String,Double]] =
    mutable.Map[Option[BeamMode], mutable.Map[String,Double]](
      Some(TRANSIT)           -> mutable.Map[String,Double](
        "gender" -> modalBehaviors.otherMultiplier.transit.gender,
        "age_30_to_50"-> modalBehaviors.otherMultiplier.transit.age_30_to_50,
        "age_50_to_70"-> modalBehaviors.otherMultiplier.transit.age_50_to_70,
        "age_70_over"-> modalBehaviors.otherMultiplier.transit.age_70_over,
        "income_under_35k"-> modalBehaviors.otherMultiplier.transit.income_under_35k,
        "income_35_to_100k"-> modalBehaviors.otherMultiplier.transit.income_35_to_100k,
        "income_100k_more"-> modalBehaviors.otherMultiplier.transit.income_100k_more,
        "workTrip"-> modalBehaviors.otherMultiplier.transit.workTrip,
        "linkToTransit"-> modalBehaviors.otherMultiplier.transit.linkToTransit,
        "car_owner"-> modalBehaviors.otherMultiplier.transit.car_owner
      ),
      Some(RIDE_HAIL)         -> mutable.Map[String,Double](
        "gender" -> modalBehaviors.otherMultiplier.rideHail.gender,
        "age_30_to_50"->modalBehaviors.otherMultiplier.rideHail.age_30_to_50,
        "age_50_to_70"-> modalBehaviors.otherMultiplier.rideHail.age_50_to_70,
        "age_70_over"-> modalBehaviors.otherMultiplier.rideHail.age_70_over,
        "income_under_35k"->modalBehaviors.otherMultiplier.rideHail.income_under_35k,
        "income_35_to_100k"->modalBehaviors.otherMultiplier.rideHail.income_35_to_100k,
        "income_100k_more"->modalBehaviors.otherMultiplier.rideHail.income_100k_more,
        "workTrip"->modalBehaviors.otherMultiplier.rideHail.workTrip,
        "linkToTransit"->modalBehaviors.otherMultiplier.rideHail.linkToTransit,
        "car_owner"-> modalBehaviors.otherMultiplier.rideHail.car_owner
      ),
      Some(RIDE_HAIL_POOLED)  -> mutable.Map[String,Double](
        "gender" -> modalBehaviors.otherMultiplier.rideHailPooled.gender,
        "age_30_to_50"->modalBehaviors.otherMultiplier.rideHailPooled.age_30_to_50,
        "age_50_to_70"-> modalBehaviors.otherMultiplier.rideHailPooled.age_50_to_70,
        "age_70_over"-> modalBehaviors.otherMultiplier.rideHailPooled.age_70_over,
        "income_under_35k"->modalBehaviors.otherMultiplier.rideHailPooled.income_under_35k,
        "income_35_to_100k"->modalBehaviors.otherMultiplier.rideHailPooled.income_35_to_100k,
        "income_100k_more"->modalBehaviors.otherMultiplier.rideHailPooled.income_100k_more,
        "workTrip"->modalBehaviors.otherMultiplier.rideHailPooled.workTrip,
        "linkToTransit"->modalBehaviors.otherMultiplier.rideHailPooled.linkToTransit,
        "car_owner"-> modalBehaviors.otherMultiplier.rideHailPooled.car_owner
      ),
      Some(RIDE_HAIL_TRANSIT) -> mutable.Map[String,Double](
        "gender" -> modalBehaviors.otherMultiplier.rideHailTransit.gender,
        "age_30_to_50"->modalBehaviors.otherMultiplier.rideHailTransit.age_30_to_50,
        "age_50_to_70"-> modalBehaviors.otherMultiplier.rideHailTransit.age_50_to_70,
        "age_70_over"-> modalBehaviors.otherMultiplier.rideHailTransit.age_70_over,
        "income_under_35k"->modalBehaviors.otherMultiplier.rideHailTransit.income_under_35k,
        "income_35_to_100k"->modalBehaviors.otherMultiplier.rideHailTransit.income_35_to_100k,
        "income_100k_more"->modalBehaviors.otherMultiplier.rideHailTransit.income_100k_more,
        "workTrip"->modalBehaviors.otherMultiplier.rideHailTransit.workTrip,
        "linkToTransit"->modalBehaviors.otherMultiplier.rideHailTransit.linkToTransit,
        "car_owner"-> modalBehaviors.otherMultiplier.rideHailTransit.car_owner
      ),
      Some(CAV)               -> mutable.Map[String,Double](
        "gender" -> modalBehaviors.otherMultiplier.CAV.gender,
        "age_30_to_50"->modalBehaviors.otherMultiplier.CAV.age_30_to_50,
        "age_50_to_70"-> modalBehaviors.otherMultiplier.CAV.age_50_to_70,
        "age_70_over"-> modalBehaviors.otherMultiplier.CAV.age_70_over,
        "income_under_35k"->modalBehaviors.otherMultiplier.CAV.income_under_35k,
        "income_35_to_100k"->modalBehaviors.otherMultiplier.CAV.income_35_to_100k,
        "income_100k_more"->modalBehaviors.otherMultiplier.CAV.income_100k_more,
        "workTrip"->modalBehaviors.otherMultiplier.CAV.workTrip,
        "linkToTransit"->modalBehaviors.otherMultiplier.CAV.linkToTransit,
        "car_owner"-> modalBehaviors.otherMultiplier.CAV.car_owner
      ),
      //      Some(WAITING)          -> modalBehaviors.modeVotMultiplier.waiting, TODO think of alternative for waiting. For now assume "NONE" is waiting
      Some(BIKE) -> mutable.Map[String,Double](
        "gender" -> modalBehaviors.otherMultiplier.bike.gender,
        "age_30_to_50"->modalBehaviors.otherMultiplier.bike.age_30_to_50,
        "age_50_to_70"-> modalBehaviors.otherMultiplier.bike.age_50_to_70,
        "age_70_over"-> modalBehaviors.otherMultiplier.bike.age_70_over,
        "income_under_35k"->modalBehaviors.otherMultiplier.bike.income_under_35k,
        "income_35_to_100k"->modalBehaviors.otherMultiplier.bike.income_35_to_100k,
        "income_100k_more"->modalBehaviors.otherMultiplier.bike.income_100k_more,
        "workTrip"->modalBehaviors.otherMultiplier.bike.workTrip,
        "linkToTransit"->modalBehaviors.otherMultiplier.bike.linkToTransit,
        "car_owner"-> modalBehaviors.otherMultiplier.bike.car_owner
      ),
      Some(WALK) -> mutable.Map[String,Double](
        "gender" -> modalBehaviors.otherMultiplier.walk.gender,
        "age_30_to_50"->modalBehaviors.otherMultiplier.walk.age_30_to_50,
        "age_50_to_70"-> modalBehaviors.otherMultiplier.walk.age_50_to_70,
        "age_70_over"-> modalBehaviors.otherMultiplier.walk.age_70_over,
        "income_under_35k"->modalBehaviors.otherMultiplier.walk.income_under_35k,
        "income_35_to_100k"->modalBehaviors.otherMultiplier.walk.income_35_to_100k,
        "income_100k_more"->modalBehaviors.otherMultiplier.walk.income_100k_more,
        "workTrip"->modalBehaviors.otherMultiplier.walk.workTrip,
        "linkToTransit"->modalBehaviors.otherMultiplier.walk.linkToTransit,
        "car_owner"-> modalBehaviors.otherMultiplier.walk.car_owner
      ),
      Some(CAR)  -> mutable.Map[String,Double](
        "gender" -> modalBehaviors.otherMultiplier.drive.gender,
        "age_30_to_50"->modalBehaviors.otherMultiplier.drive.age_30_to_50,
        "age_50_to_70"-> modalBehaviors.otherMultiplier.drive.age_50_to_70,
        "age_70_over"-> modalBehaviors.otherMultiplier.drive.age_70_over,
        "income_under_35k"->modalBehaviors.otherMultiplier.drive.income_under_35k,
        "income_35_to_100k"->modalBehaviors.otherMultiplier.drive.income_35_to_100k,
        "income_100k_more"->modalBehaviors.otherMultiplier.drive.income_100k_more,
        "workTrip"->modalBehaviors.otherMultiplier.drive.workTrip,
        "linkToTransit"->modalBehaviors.otherMultiplier.drive.linkToTransit,
        "car_owner"-> modalBehaviors.otherMultiplier.drive.car_owner
      ),
      None       -> mutable.Map[String,Double](
        "gender" -> modalBehaviors.otherMultiplier.waiting.gender,
        "age_30_to_50"->modalBehaviors.otherMultiplier.waiting.age_30_to_50,
        "age_50_to_70"-> modalBehaviors.otherMultiplier.waiting.age_50_to_70,
        "age_70_over"-> modalBehaviors.otherMultiplier.waiting.age_70_over,
        "income_under_35k"->modalBehaviors.otherMultiplier.waiting.income_under_35k,
        "income_35_to_100k"->modalBehaviors.otherMultiplier.waiting.income_35_to_100k,
        "income_100k_more"->modalBehaviors.otherMultiplier.waiting.income_100k_more,
        "workTrip"->modalBehaviors.otherMultiplier.waiting.workTrip,
        "linkToTransit"->modalBehaviors.otherMultiplier.waiting.linkToTransit,
        "car_owner"-> modalBehaviors.otherMultiplier.waiting.car_owner
      )
    )

  override def utilityOf(
    alternative: EmbodiedBeamTrip,
    attributesOfIndividual: AttributesOfIndividual,
    destinationActivity: Option[Activity]
  ): Double = {
    val modeCostTimeTransfer =
      altsToModeCostTimeTransfers(IndexedSeq(alternative), attributesOfIndividual, destinationActivity).head
    utilityOf(modeCostTimeTransfer)
  }

  private def utilityOf(mct: ModeCostTimeTransfer): Double = {
    model
      .getUtilityOfAlternative(mct.embodiedBeamTrip, attributes(mct.cost, mct.transitOccupancyLevel, mct.numTransfers))
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
    trips: ListBuffer[EmbodiedBeamTrip],
    person: Person,
    attributesOfIndividual: AttributesOfIndividual
  ): Double = trips.map(utilityOf(_, attributesOfIndividual, None)).sum // TODO: Update with destination activity
}

object ModeChoiceMultinomialLogit extends StrictLogging {

  def buildModelFromConfig(
    configHolder: BeamConfigHolder
  ): (MultinomialLogit[EmbodiedBeamTrip, String], MultinomialLogit[BeamMode, String]) = {

    val params = configHolder.beamConfig.beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params
    val commonUtility: Map[String, UtilityFunctionOperation] = Map(
      "cost" -> UtilityFunctionOperation("multiplier", -1)
    )
    val scale_factor: Double =
      configHolder.beamConfig.beam.agentsim.agents.modalBehaviors.mulitnomialLogit.utility_scale_factor

    val carIntercept = Map("intercept" -> UtilityFunctionOperation("intercept", params.car_intercept))
    val mnlUtilityFunctions: Map[String, Map[String, UtilityFunctionOperation]] = Map(
      BeamMode.CAR.value      -> carIntercept,
      BeamMode.CAR_HOV2.value -> carIntercept,
      BeamMode.CAR_HOV3.value -> carIntercept,
      "cav"                   -> Map("intercept" -> UtilityFunctionOperation("intercept", params.cav_intercept)),
      "walk"                  -> Map("intercept" -> UtilityFunctionOperation("intercept", params.walk_intercept)),
      "ride_hail"             -> Map("intercept" -> UtilityFunctionOperation("intercept", params.ride_hail_intercept)),
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
    embodiedBeamTrip: EmbodiedBeamTrip,
    cost: Double,
    scaledTime: Double,
    numTransfers: Int,
    transitOccupancyLevel: Double,
    index: Int = -1
  )

  def getTransitVehicleTypeVOTMultipliers(
    vehicleTypes: Map[Id[BeamVehicleType], BeamVehicleType],
    transitVehicleTypeVOTMultipliersStr: List[String]
  ): Map[Id[BeamVehicleType], Double] = {
    if (transitVehicleTypeVOTMultipliersStr.isEmpty) Map.empty
    else {
      val vehTypeToMultiplier = transitVehicleTypeVOTMultipliersStr.flatMap { curr =>
        val separator = curr.indexOf(":")
        if (separator < 0) {
          logger.warn(
            s"Cannot derive vehicle mode and multiplier from '${transitVehicleTypeVOTMultipliersStr}', current element is '${curr}'"
          )
          None
        } else {
          val vehicleTypeStr = curr.substring(0, separator)
          val vehicleTypeId = Id.create(vehicleTypeStr, classOf[BeamVehicleType])
          vehicleTypes.get(vehicleTypeId) match {
            case Some(_) =>
              val multiplier = curr.substring(separator + 1).toDouble
              Some((vehicleTypeId, multiplier))
            case None =>
              logger.warn(s"Can't find vehicle type '${vehicleTypeStr}'")
              None
          }

        }
      }
      vehTypeToMultiplier.toMap
    }
  }

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
