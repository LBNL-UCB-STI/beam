package beam.agentsim.agents.modalBehaviors

import beam.agentsim.agents.choice.logit.LatentClassChoiceModel
import beam.agentsim.agents.choice.mode._
import beam.agentsim.agents.household.HouseholdActor.AttributesOfIndividual
import beam.router.RoutingModel.EmbodiedBeamTrip
import beam.sim.{BeamServices, HasServices}
import org.matsim.api.core.v01.population.Person

import scala.util.Random

/**
  * BEAM
  */
trait ModeChoiceCalculator extends HasServices {

  def apply(alternatives: Seq[EmbodiedBeamTrip]): EmbodiedBeamTrip

  def utilityOf(alternative: EmbodiedBeamTrip): Double

  final def chooseRandomAlternativeIndex(alternatives: Seq[EmbodiedBeamTrip]): Int = {
    if (alternatives.nonEmpty) {
      Random.nextInt(alternatives.size)
    } else {
      throw new IllegalArgumentException("Cannot choose from an empty choice set.")
    }
  }
}

object ModeChoiceCalculator {
  def apply(classname: String, beamServices: BeamServices): AttributesOfIndividual => ModeChoiceCalculator = {
    classname match {
      case "ModeChoiceLCCM" =>
        (attributesOfIndividual: AttributesOfIndividual) => new ModeChoiceLCCM(beamServices, new LatentClassChoiceModel(beamServices), Some(attributesOfIndividual))
      case "ModeChoiceTransitIfAvailable" =>
        (_) => new ModeChoiceTransitIfAvailable(beamServices)
      case "ModeChoiceDriveIfAvailable" =>
        (_) => new ModeChoiceDriveIfAvailable(beamServices)
      case "ModeChoiceRideHailIfAvailable" =>
        (_) => new ModeChoiceRideHailIfAvailable(beamServices)
      case "ModeChoiceUniformRandom" =>
        (_) => new ModeChoiceUniformRandom(beamServices)
      case "ModeChoiceMultinomialLogit" =>
        (_) => ModeChoiceMultinomialLogit(beamServices)
      case "ModeChoiceMultinomialLogitTest" =>
        (_) => ModeChoiceMultinomialLogit(beamServices)
    }
  }


}

