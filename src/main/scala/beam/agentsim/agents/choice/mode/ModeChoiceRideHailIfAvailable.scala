package beam.agentsim.agents.choice.mode

import beam.agentsim.agents.modalBehaviors.ModeChoiceCalculator
import beam.router.Modes
import beam.router.Modes.BeamMode.RIDE_HAIL
import beam.router.RoutingModel.EmbodiedBeamTrip
import beam.sim.BeamServices

/**
  * BEAM
  */
class ModeChoiceRideHailIfAvailable(val beamServices: BeamServices) extends ModeChoiceCalculator {

  override def apply(alternatives: Seq[EmbodiedBeamTrip]): Option[EmbodiedBeamTrip] = {
    val containsRideHailAlt = alternatives.zipWithIndex.collect {
      case (trip, idx) if trip.tripClassifier == RIDE_HAIL => idx
    }
    if (containsRideHailAlt.nonEmpty) {
      Some(alternatives(containsRideHailAlt.head))
    } else if (alternatives.nonEmpty) {
      Some(alternatives(chooseRandomAlternativeIndex(alternatives)))
    } else {
      None
    }
  }

  override def utilityOf(alternative: EmbodiedBeamTrip): Double = 0.0

  override def utilityOf(mode: Modes.BeamMode, cost: Double, time: Double, numTransfers: Int): Double = 0.0
}
