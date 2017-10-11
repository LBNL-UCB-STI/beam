package beam.agentsim.agents.choice.mode

import beam.agentsim.agents.choice.logit.LatentClassChoiceModel
import beam.agentsim.agents.modalBehaviors.ModeChoiceCalculator
import beam.router.RoutingModel.EmbodiedBeamTrip
import beam.sim.BeamServices

/**
  * BEAM
  */
class ModeChoiceLCCM(val beamServices: BeamServices, val lccm: LatentClassChoiceModel) extends ModeChoiceCalculator {

  override def apply(alternatives: Vector[EmbodiedBeamTrip]): Option[EmbodiedBeamTrip] = {
    Some(alternatives.find(a => a.tripClassifier.isTransit).getOrElse(EmbodiedBeamTrip.empty))
  }

  override def clone(): ModeChoiceCalculator = {
    val lccmClone: LatentClassChoiceModel = lccm.clone().asInstanceOf[LatentClassChoiceModel]
    new ModeChoiceLCCM(beamServices,lccmClone)
  }
}
object ModeChoiceLCCM{
  def apply(beamServices: BeamServices): ModeChoiceLCCM ={
    val lccm = new LatentClassChoiceModel(beamServices)
    new ModeChoiceLCCM(beamServices,lccm)
  }

}
