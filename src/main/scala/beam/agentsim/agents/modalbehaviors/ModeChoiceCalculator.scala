package beam.agentsim.agents.modalbehaviors

import beam.agentsim.agents.choice.logit.LatentClassChoiceModel
import beam.agentsim.agents.choice.logit.LatentClassChoiceModel.Mandatory
import beam.agentsim.agents.choice.mode._
import beam.agentsim.agents.household.HouseholdActor.AttributesOfIndividual
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{
  BIKE,
  CAR,
  DRIVE_TRANSIT,
  RIDE_HAIL,
  RIDE_HAIL_TRANSIT,
  WALK,
  WALK_TRANSIT
}
import beam.router.RoutingModel.EmbodiedBeamTrip
import beam.sim.config.BeamConfig
import beam.sim.{BeamServices, HasServices}
import beam.utils.MathUtils

import scala.collection.mutable
import scala.util.Random

/**
  * BEAM
  */
trait ModeChoiceCalculator extends HasServices {
  import ModeChoiceCalculator._

  implicit lazy val random: Random = new Random(
    beamServices.beamConfig.matsim.modules.global.randomSeed
  )

  /// VOT-Specific fields and methods

  /**
    * Adds heterogeneous VOT to mode choice computation.
    *
    * Implemented as a scaling factor on cost parameters. Default value of time is added at initialization.
    */
  // Note: We use BigDecimal here as we're dealing with monetary values requiring exact precision.
  // Could be refactored if this is a performance issue, but prefer not to.
  lazy val _valuesOfTime: mutable.Map[VotType, BigDecimal] =
    mutable.Map[VotType, BigDecimal](
      DefaultVot -> MathUtils.roundDouble(
        try{
          beamServices.beamConfig.beam.agentsim.agents.modalBehaviors.defaultValueOfTime
        }
        catch {case e: NullPointerException => 18.0},
        -3
      )
    )

  /**
    * Converts [[BeamMode BeamModes]] into their appropriate [[VotType VotTypes]].
    *
    * This level of indirection is used in order ot abstract the
    *  details of the VOT logic from the business logic.
    *
    * @param beamMode The [[BeamMode]] to convert.
    * @return the target [[VotType]].
    */
  // NOTE: Could have implemented as a Map[BeamMode->VotType], but prefer exhaustive
  // matching enforced by sealed traits.
  private def matchMode2Vot(beamMode: Option[BeamMode]): VotType = beamMode match {
    case Some(CAR)                                        => DriveVot
    case Some(WALK)                                       => WalkVot
    case Some(BIKE)                                       => BikeVot
    case Some(WALK_TRANSIT)                               => WalkToTransitVot
    case Some(DRIVE_TRANSIT)                              => DriveToTransitVot
    case Some(RIDE_HAIL)                                  => RideHailVot
    case a @ Some(_) if BeamMode.transitModes.contains(a) => OnTransitVot
    case Some(RIDE_HAIL_TRANSIT)                          => RideHailVot
    case None                                             => GeneralizedVot
  }

  // NOTE: If the generalized value of time is not yet instantiated, then this will return
  // the default VOT as defined in the config.
  private def getVot(beamMode: Option[BeamMode]): BigDecimal =
    _valuesOfTime.getOrElse(
      matchMode2Vot(beamMode),
      _valuesOfTime.getOrElse(GeneralizedVot, _valuesOfTime(DefaultVot))
    )

  def setVot(value: BigDecimal, beamMode: Option[BeamMode] = None): _valuesOfTime.type = {
    _valuesOfTime += matchMode2Vot(beamMode) -> value
  }

  def scaleTimeByVot(time: BigDecimal, beamMode: Option[BeamMode] = None): BigDecimal = {
    time / 3600 * getVot(beamMode)
  }
  ///~

  def apply(alternatives: Seq[EmbodiedBeamTrip]): Option[EmbodiedBeamTrip]

  def utilityOf(alternative: EmbodiedBeamTrip): Double

  def utilityOf(mode: BeamMode, cost: BigDecimal, time: BigDecimal, numTransfers: Int = 0): Double

  final def chooseRandomAlternativeIndex(alternatives: Seq[EmbodiedBeamTrip]): Int = {
    if (alternatives.nonEmpty) {
      Random.nextInt(alternatives.size)
    } else {
      throw new IllegalArgumentException("Cannot choose from an empty choice set.")
    }
  }
}

object ModeChoiceCalculator {

  sealed trait VotType
  private case object DefaultVot extends VotType
  private case object GeneralizedVot extends VotType

  // TODO: Implement usage of mode-specific VotTypes defined below
  private case object DriveVot extends VotType
  private case object OnTransitVot extends VotType
  private case object WalkVot extends VotType
  private case object WalkToTransitVot extends VotType // Separate from walking
  private case object DriveToTransitVot extends VotType
  private case object RideHailVot extends VotType // No separate ride hail to transit VOT
  private case object BikeVot extends VotType

  type ModeChoiceCalculatorFactory = AttributesOfIndividual => ModeChoiceCalculator

  def apply(classname: String, beamServices: BeamServices): ModeChoiceCalculatorFactory = {
    classname match {
      case "ModeChoiceLCCM" =>
        val lccm = new LatentClassChoiceModel(beamServices)
        (attributesOfIndividual: AttributesOfIndividual) =>
          attributesOfIndividual match {
            case AttributesOfIndividual(_, _, _, Some(modalityStyle), _, _, _) =>
              new ModeChoiceMultinomialLogit(
                beamServices,
                lccm.modeChoiceModels(Mandatory)(modalityStyle)
              )
            case _ =>
              throw new RuntimeException("LCCM needs people to have modality styles")
          }
      case "ModeChoiceTransitIfAvailable" =>
        (_) =>
          new ModeChoiceTransitIfAvailable(beamServices)
      case "ModeChoiceDriveIfAvailable" =>
        (_) =>
          new ModeChoiceDriveIfAvailable(beamServices)
      case "ModeChoiceRideHailIfAvailable" =>
        (_) =>
          new ModeChoiceRideHailIfAvailable(beamServices)
      case "ModeChoiceUniformRandom" =>
        (_) =>
          new ModeChoiceUniformRandom(beamServices)
      case "ModeChoiceMultinomialLogit" =>
        val logit = ModeChoiceMultinomialLogit.buildModelFromConfig(
          beamServices.beamConfig.beam.agentsim.agents.modalBehaviors.mulitnomialLogit
        )
        (_) =>
          new ModeChoiceMultinomialLogit(beamServices, logit)
      case "ModeChoiceMultinomialLogitTest" =>
        val logit = ModeChoiceMultinomialLogit.buildModelFromConfig(
          beamServices.beamConfig.beam.agentsim.agents.modalBehaviors.mulitnomialLogit
        )
        (_) =>
          new ModeChoiceMultinomialLogit(beamServices, logit)
    }
  }

}
