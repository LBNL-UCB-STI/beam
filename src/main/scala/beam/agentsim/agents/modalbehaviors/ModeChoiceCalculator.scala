package beam.agentsim.agents.modalbehaviors

import beam.agentsim.agents.choice.logit.LatentClassChoiceModel
import beam.agentsim.agents.choice.logit.LatentClassChoiceModel.Mandatory
import beam.agentsim.agents.choice.mode._
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode._
import beam.router.model.{EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.router.skim.TransitCrowdingSkims
import beam.sim.BeamServices
import beam.sim.config.{BeamConfig, BeamConfigHolder}
import beam.sim.population.AttributesOfIndividual
import org.matsim.api.core.v01.population.{Activity, Person}
import org.matsim.core.api.experimental.events.EventsManager

import scala.collection.mutable.ListBuffer
import scala.util.Random

/**
  * BEAM
  */
trait ModeChoiceCalculator {

  val beamConfig: BeamConfig
  lazy val random: Random = new Random(
    beamConfig.matsim.modules.global.randomSeed
  )

  def getGeneralizedTimeOfTrip(
    embodiedBeamTrip: EmbodiedBeamTrip,
    attributesOfIndividual: Option[AttributesOfIndividual] = None,
    destinationActivity: Option[Activity] = None
  ): Double = {
    embodiedBeamTrip.totalTravelTimeInSecs / 3600
  }

  def getGeneralizedTimeOfLeg(
    embodiedBeamLeg: EmbodiedBeamLeg,
    attributesOfIndividual: Option[AttributesOfIndividual],
    destinationActivity: Option[Activity]
  ): Double = {
    embodiedBeamLeg.beamLeg.duration / 3600
  }

  def getGeneralizedTime(
    time: Double,
    beamMode: Option[BeamMode] = None,
    beamLeg: Option[EmbodiedBeamLeg] = None
  ): Double = {
    time / 3600
  }

  def apply(
    alternatives: IndexedSeq[EmbodiedBeamTrip],
    attributesOfIndividual: AttributesOfIndividual,
    destinationActivity: Option[Activity],
    person: Option[Person] = None
  ): Option[EmbodiedBeamTrip]

  def utilityOf(
    alternative: EmbodiedBeamTrip,
    attributesOfIndividual: AttributesOfIndividual,
    destinationActivity: Option[Activity]
  ): Double

  def utilityOf(
    mode: BeamMode,
    cost: Double,
    time: Double,
    numTransfers: Int = 0,
    transitOccupancyLevel: Double = 0.0
  ): Double

  def getNonTimeCost(embodiedBeamTrip: EmbodiedBeamTrip, includeReplanningPenalty: Boolean = false): Double = {

    val totalCost = embodiedBeamTrip.tripClassifier match {
      case TRANSIT | WALK_TRANSIT | DRIVE_TRANSIT =>
        val transitFareDefault =
          TransitFareDefaults.estimateTransitFares(IndexedSeq(embodiedBeamTrip)).head
        (embodiedBeamTrip.costEstimate + transitFareDefault) * beamConfig.beam.agentsim.tuning.transitPrice
      case RIDE_HAIL | RIDE_HAIL_POOLED =>
        val rideHailDefault = RideHailDefaults.estimateRideHailCost(IndexedSeq(embodiedBeamTrip)).head
        (embodiedBeamTrip.costEstimate + rideHailDefault) * beamConfig.beam.agentsim.tuning.rideHailPrice
      case RIDE_HAIL_TRANSIT =>
        val transitFareDefault =
          TransitFareDefaults.estimateTransitFares(IndexedSeq(embodiedBeamTrip)).head
        val rideHailDefault = RideHailDefaults.estimateRideHailCost(IndexedSeq(embodiedBeamTrip)).head
        (embodiedBeamTrip.legs.view
          .filter(_.beamLeg.mode.isTransit)
          .map(_.cost)
          .sum + transitFareDefault) * beamConfig.beam.agentsim.tuning.transitPrice +
        (embodiedBeamTrip.legs.view
          .filter(_.isRideHail)
          .map(_.cost)
          .sum + rideHailDefault * beamConfig.beam.agentsim.tuning.rideHailPrice)
      case _ =>
        embodiedBeamTrip.costEstimate
    }
    if (includeReplanningPenalty) {
      totalCost + embodiedBeamTrip.replanningPenalty
    } else { totalCost }
  }

  def computeAllDayUtility(
    trips: ListBuffer[EmbodiedBeamTrip],
    person: Person,
    attributesOfIndividual: AttributesOfIndividual
  ): Double

  final def chooseRandomAlternativeIndex(alternatives: Seq[EmbodiedBeamTrip]): Int = {
    if (alternatives.nonEmpty) {
      Random.nextInt(alternatives.size)
    } else {
      throw new IllegalArgumentException("Cannot choose from an empty choice set.")
    }
  }
}

object ModeChoiceCalculator {

  type ModeChoiceCalculatorFactory = AttributesOfIndividual => ModeChoiceCalculator

  def apply(
    classname: String,
    beamServices: BeamServices,
    configHolder: BeamConfigHolder,
    eventsManager: EventsManager
  ): ModeChoiceCalculatorFactory = {
    classname match {
      case "ModeChoiceLCCM" =>
        val lccm = new LatentClassChoiceModel(beamServices)
        (attributesOfIndividual: AttributesOfIndividual) =>
          attributesOfIndividual match {
            case AttributesOfIndividual(_, Some(modalityStyle), _, _, _, _, _) =>
              val (model, modeModel) = lccm.modeChoiceModels(Mandatory)(modalityStyle)
              new ModeChoiceMultinomialLogit(
                beamServices,
                model,
                modeModel,
                configHolder,
                beamServices.skims.tc_skimmer,
                eventsManager
              )
            case _ =>
              throw new RuntimeException("LCCM needs people to have modality styles")
          }
      case "ModeChoiceTransitIfAvailable" =>
        _ =>
          new ModeChoiceTransitIfAvailable(beamServices)
      case "ModeChoiceDriveIfAvailable" =>
        _ =>
          new ModeChoiceDriveIfAvailable(beamServices)
      case "ModeChoiceRideHailIfAvailable" =>
        _ =>
          new ModeChoiceRideHailIfAvailable(beamServices)
      case "ModeChoiceUniformRandom" =>
        _ =>
          new ModeChoiceUniformRandom(beamServices.beamConfig)
      case "ModeChoiceMultinomialLogit" =>
        val (routeLogit, modeLogit) = ModeChoiceMultinomialLogit.buildModelFromConfig(configHolder)
        _ =>
          new ModeChoiceMultinomialLogit(
            beamServices,
            routeLogit,
            modeLogit,
            configHolder,
            beamServices.skims.tc_skimmer,
            eventsManager
          )
    }
  }
  sealed trait ModeVotMultiplier
  case object Drive extends ModeVotMultiplier
  case object OnTransit extends ModeVotMultiplier
  case object Walk extends ModeVotMultiplier
  case object WalkToTransit extends ModeVotMultiplier // Separate from walking
  case object DriveToTransit extends ModeVotMultiplier
  case object RideHail extends ModeVotMultiplier // No separate ride hail to transit VOT
  case object Bike extends ModeVotMultiplier
  sealed trait timeSensitivity
  case object highSensitivity extends timeSensitivity
  case object lowSensitivity extends timeSensitivity
  sealed trait congestionLevel
  case object highCongestion extends congestionLevel
  case object lowCongestion extends congestionLevel
  sealed trait roadwayType
  case object highway extends roadwayType
  case object nonHighway extends roadwayType
  sealed trait automationLevel
  case object levelLE2 extends automationLevel
  case object level3 extends automationLevel
  case object level4 extends automationLevel
  case object level5 extends automationLevel
}
