package beam.agentsim.agents.choice.mode

import beam.agentsim.agents.modalBehaviors.ModeChoiceCalculator
import beam.agentsim.agents.modalBehaviors.ModeChoiceCalculator.AttributesOfIndividual
import beam.router.Modes.BeamMode.{CAR, RIDEHAIL}
import beam.router.RoutingModel.EmbodiedBeamTrip
import beam.sim.BeamServices

/**
  * BEAM
  */
class ModeChoiceRideHailOnly(val beamServices: BeamServices) extends ModeChoiceCalculator {

  override def clone(): ModeChoiceCalculator = new ModeChoiceRideHailOnly(beamServices)
  override def apply(alternatives: Vector[EmbodiedBeamTrip], choiceAttributes: Option[AttributesOfIndividual]) = {
    val rhAlts = alternatives.filter(_.tripClassifier == RIDEHAIL)
    rhAlts.isEmpty match {
      case true =>
        None
      case false =>
        Some(rhAlts.head)
    }
  }

}
