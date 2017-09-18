package beam.agentsim.agents.choice.mode

import beam.agentsim.agents.modalBehaviors.ModeChoiceCalculator
import beam.router.Modes.BeamMode.{CAR, RIDEHAIL}
import beam.router.RoutingModel.EmbodiedBeamTrip
import beam.sim.BeamServices

/**
  * BEAM
  */
class ModeChoiceRideHailOnly(val beamServices: BeamServices) extends ModeChoiceCalculator {

  override def apply(alternatives: Vector[EmbodiedBeamTrip]) = {
    val rhAlts = alternatives.filter(_.tripClassifier == RIDEHAIL)
    rhAlts.isEmpty match {
      case true =>
        None
      case false =>
        Some(rhAlts.head)
    }
  }

}
