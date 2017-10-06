package beam.agentsim.agents.choice.mode

import beam.agentsim.agents.modalBehaviors.ModeChoiceCalculator
import beam.router.Modes.BeamMode.CAR
import beam.router.RoutingModel.EmbodiedBeamTrip
import beam.sim.BeamServices

/**
  * BEAM
  */
class ModeChoiceDriveOnly(val beamServices: BeamServices) extends ModeChoiceCalculator {

  override def clone(): ModeChoiceCalculator = new ModeChoiceDriveOnly(beamServices)
  override def apply(alternatives: Vector[EmbodiedBeamTrip]) = {
    val carAlts = alternatives.filter(_.tripClassifier == CAR)
    carAlts.isEmpty match {
      case true =>
        None
      case false =>
        Some(carAlts.head)
    }
  }

}
