package beam.agentsim.agents.choice.mode

import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator
import beam.router.Modes
import beam.router.RoutingModel.EmbodiedBeamTrip
import beam.sim.BeamServices

/**
  * BEAM
  */
class ModeChoiceTransitIfAvailable(val beamServices: BeamServices)
    extends ModeChoiceCalculator {

  override def clone(): ModeChoiceCalculator =
    new ModeChoiceTransitIfAvailable(beamServices)

  override def apply(alternatives: IndexedSeq[EmbodiedBeamTrip]): Option[EmbodiedBeamTrip] = {
    val containsTransitAlt = alternatives.zipWithIndex.collect {
      case (trip, idx) if trip.tripClassifier.isTransit => idx
    }
    if (containsTransitAlt.nonEmpty) {
      Some(alternatives(containsTransitAlt.head))
    } else if (alternatives.nonEmpty) {
      Some(alternatives(chooseRandomAlternativeIndex(alternatives)))
    } else {
      None
    }
  }

  override def utilityOf(alternative: EmbodiedBeamTrip): Double = 0.0

  override def utilityOf(
    mode: Modes.BeamMode,
    cost: BigDecimal,
    time: BigDecimal,
    numTransfers: Int
  ): Double = 0.0
}

object ModeChoiceTransitIfAvailable {}
