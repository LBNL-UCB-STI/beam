package beam.agentsim.agents.choice.mode

import beam.agentsim.agents.modalBehaviors.ModeChoiceCalculator
import beam.router.Modes
import beam.router.RoutingModel.EmbodiedBeamTrip
import beam.sim.BeamServices

/**
  * BEAM
  */
class ModeChoiceTransitIfAvailable(val beamServices: BeamServices) extends ModeChoiceCalculator {

  override def clone(): ModeChoiceCalculator = new ModeChoiceTransitIfAvailable(beamServices)

  override def apply(alternatives: Seq[EmbodiedBeamTrip]) = {
    var containsTransitAlt: Vector[Int] = Vector[Int]()
    alternatives.zipWithIndex.foreach { alt =>
      if (alt._1.tripClassifier.isTransit) {
        containsTransitAlt = containsTransitAlt :+ alt._2
      }
    }
    Some(alternatives(if (containsTransitAlt.nonEmpty) {
      containsTransitAlt.head
    }
    else {
      chooseRandomAlternativeIndex(alternatives)
    }))
  }

  override def utilityOf(alternative: EmbodiedBeamTrip): Double = 0.0
  override def utilityOf(mode: Modes.BeamMode, cost: Double, time: Double, numTransfers: Int): Double = 0.0
}
