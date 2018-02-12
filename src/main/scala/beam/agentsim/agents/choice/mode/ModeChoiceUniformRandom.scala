package beam.agentsim.agents.choice.mode

import beam.agentsim.agents.household.HouseholdActor.AttributesOfIndividual
import beam.agentsim.agents.modalBehaviors.ModeChoiceCalculator
import beam.router.RoutingModel.EmbodiedBeamTrip
import beam.sim.BeamServices

/**
  * BEAM
  */
class ModeChoiceUniformRandom(val beamServices: BeamServices) extends ModeChoiceCalculator {

  override def apply(alternatives: Seq[EmbodiedBeamTrip]) = {
    alternatives(chooseRandomAlternativeIndex(alternatives))
  }

  override def utilityOf(alternative: EmbodiedBeamTrip): Double = 0.0
}
