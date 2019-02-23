package beam.agentsim.agents.modalbehaviors

import beam.agentsim.agents.choice.logit.LatentClassChoiceModel
import beam.agentsim.agents.choice.logit.LatentClassChoiceModel.Mandatory
import beam.agentsim.agents.choice.mode._
import beam.router.Modes.BeamMode
import beam.router.model.{EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.sim.population.AttributesOfIndividual
import beam.sim.{BeamServices, HasServices}

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

  // TODO: This is depreciated, only used by LCCM
  def scaleTimeByVot(time: Double, beamMode: Option[BeamMode] = None, beamLeg: Option[EmbodiedBeamLeg] = None): Double = {
    time / 3600 * beamServices.getModeVotMultiplier(beamMode)
  }

  def apply(
    alternatives: IndexedSeq[EmbodiedBeamTrip],
    attributesOfIndividual: AttributesOfIndividual
  ): Option[EmbodiedBeamTrip]

  def utilityOf(alternative: EmbodiedBeamTrip, attributesOfIndividual: AttributesOfIndividual): Double

  def utilityOf(mode: BeamMode, cost: Double, time: Double, numTransfers: Int = 0): Double

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

  def apply(classname: String, beamServices: BeamServices): ModeChoiceCalculatorFactory = {
    classname match {
      case "ModeChoiceLCCM" =>
        val lccm = new LatentClassChoiceModel(beamServices)
        (attributesOfIndividual: AttributesOfIndividual) =>
          attributesOfIndividual match {
            case AttributesOfIndividual(_, Some(modalityStyle), _, _, _, _, _) =>
              new ModeChoiceMultinomialLogit(
                beamServices,
                lccm.modeChoiceModels(Mandatory)(modalityStyle)
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
          new ModeChoiceUniformRandom(beamServices)
      case "ModeChoiceMultinomialLogit" =>
        val logit = ModeChoiceMultinomialLogit.buildModelFromConfig(
          beamServices.beamConfig.beam.agentsim.agents.modalBehaviors.mulitnomialLogit
        )
        _ =>
          new ModeChoiceMultinomialLogit(beamServices, logit)
    }
  }
}
