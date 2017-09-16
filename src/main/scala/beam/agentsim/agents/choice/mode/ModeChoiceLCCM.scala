package beam.agentsim.agents.choice.mode

import beam.agentsim.agents.choice.logit.LatentClassChoiceModel
import beam.agentsim.agents.modalBehaviors.ModeChoiceCalculator
import beam.router.RoutingModel.EmbodiedBeamTrip
import beam.sim.BeamServices

/**
  * BEAM
  */
class ModeChoiceLCCM(val beamServices: BeamServices) extends ModeChoiceCalculator {
  val lccm = new LatentClassChoiceModel(beamServices)

  override def apply(alternatives: Vector[EmbodiedBeamTrip]) = {
    alternatives.
      find(a => a.tripClassifier.isTransit)
      .getOrElse(EmbodiedBeamTrip.empty)
  }

}
