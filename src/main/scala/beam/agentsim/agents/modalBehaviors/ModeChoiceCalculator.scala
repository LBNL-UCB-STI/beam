package beam.agentsim.agents.modalBehaviors

import beam.agentsim.agents.choice.mode._
import beam.agentsim.agents.modalBehaviors.ModeChoiceCalculator.AttributesOfIndividual
import beam.router.RoutingModel.EmbodiedBeamTrip
import beam.sim.{BeamServices, HasServices}

import scala.util.Random

/**
  * BEAM
  */
trait ModeChoiceCalculator extends HasServices with Cloneable {
  def apply(alternatives: Seq[EmbodiedBeamTrip], extraAttributes: Option[AttributesOfIndividual]): EmbodiedBeamTrip

  def apply(alternatives: Seq[EmbodiedBeamTrip]): EmbodiedBeamTrip = {
    this(alternatives, None)
  }

  final def chooseRandomAlternativeIndex(alternatives: Seq[EmbodiedBeamTrip]): Int = {
    if (alternatives.nonEmpty) {
      Random.nextInt(alternatives.size)
    } else {
      throw new IllegalArgumentException("Cannot choose from an empty choice set.")
    }
  }

  override def clone(): ModeChoiceCalculator = {
    this.clone()
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
    }
  }

  case class AttributesOfIndividual(householdIncome: Double, householdSize: Int, isMale: Boolean, numCars: Int, numBikes: Int)

}

