package beam.agentsim.agents.choice.mode

import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator
import beam.router.Modes
import beam.router.model.EmbodiedBeamTrip
import beam.sim.BeamServices
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person

/**
  * BEAM
  */
class ModeChoiceUniformRandom(val beamServices: BeamServices) extends ModeChoiceCalculator {

  override def apply(alternatives: IndexedSeq[EmbodiedBeamTrip], personId: Id[Person]): Option[EmbodiedBeamTrip] = {
    if (alternatives.nonEmpty) {
      Some(alternatives(chooseRandomAlternativeIndex(alternatives)))
    } else {
      None
    }
  }

  override def utilityOf(alternative: EmbodiedBeamTrip, personId: Id[Person]): Double = 0.0

  override def utilityOf(
    mode: Modes.BeamMode,
    cost: BigDecimal,
    time: BigDecimal,
    numTransfers: Int
  ): Double = 0.0
}
