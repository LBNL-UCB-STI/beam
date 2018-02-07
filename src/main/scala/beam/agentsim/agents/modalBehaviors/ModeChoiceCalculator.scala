package beam.agentsim.agents.modalBehaviors

import beam.agentsim.agents.choice.mode._
import beam.agentsim.agents.household.HouseholdActor.AttributesOfIndividual
import beam.router.RoutingModel.EmbodiedBeamTrip
import beam.sim.{BeamServices, HasServices}

import scala.util.Random

/**
  * BEAM
  */
trait ModeChoiceCalculator extends HasServices {
  def apply(alternatives: Seq[EmbodiedBeamTrip], extraAttributes: Option[AttributesOfIndividual]): EmbodiedBeamTrip

  def apply(alternatives: Seq[EmbodiedBeamTrip]): EmbodiedBeamTrip = {
    this (alternatives, None)
  }

  def utilityOf(alternative: EmbodiedBeamTrip): Double = {
    0.0
  }

  final def chooseRandomAlternativeIndex(alternatives: Seq[EmbodiedBeamTrip]): Int = {
    if (alternatives.nonEmpty) {
      Random.nextInt(alternatives.size)
    } else {
      throw new IllegalArgumentException("Cannot choose from an empty choice set.")
    }
  }
}

object ModeChoiceCalculator {
  def apply(classname: String, beamServices: BeamServices): ModeChoiceCalculator = {
    classname match {
      case "ModeChoiceLCCM" =>
        ModeChoiceLCCM(beamServices)
      case "ModeChoiceTransitIfAvailable" =>
        new ModeChoiceTransitIfAvailable(beamServices)
      case "ModeChoiceDriveIfAvailable" =>
        new ModeChoiceDriveIfAvailable(beamServices)
      case "ModeChoiceRideHailIfAvailable" =>
        new ModeChoiceRideHailIfAvailable(beamServices)
      case "ModeChoiceUniformRandom" =>
        new ModeChoiceUniformRandom(beamServices)
      case "ModeChoiceMultinomialLogit" =>
        ModeChoiceMultinomialLogit(beamServices)
      case "ModeChoiceMultinomialLogitTest" =>
        ModeChoiceMultinomialLogit(beamServices)
    }
  }


}

