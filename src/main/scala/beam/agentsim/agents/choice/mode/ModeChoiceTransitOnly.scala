package beam.agentsim.agents.choice.mode

import beam.agentsim.agents.modalBehaviors.ModeChoiceCalculator
import beam.router.Modes
import beam.router.Modes.BeamMode.CAR
import beam.router.RoutingModel.EmbodiedBeamTrip
import beam.sim.BeamServices

/**
  * BEAM
  */
class ModeChoiceTransitOnly(val beamServices: BeamServices) extends ModeChoiceCalculator {

  override def apply(alternatives: Vector[EmbodiedBeamTrip]) = {
    val transitAlts = alternatives.filter(alt => Modes.isR5TransitMode(alt.tripClassifier))
    transitAlts.isEmpty match {
      case true =>
        None
      case false =>
        Some(transitAlts.head)
    }
  }

}
