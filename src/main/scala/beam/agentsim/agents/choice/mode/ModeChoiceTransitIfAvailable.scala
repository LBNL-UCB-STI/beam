package beam.agentsim.agents.choice.mode

import beam.agentsim.agents.modalBehaviors.{ChoiceAttributes, ModeChoiceCalculator}
import beam.router.RoutingModel.EmbodiedBeamTrip
import beam.sim.BeamServices

/**
  * BEAM
  */
class ModeChoiceTransitIfAvailable(val beamServices: BeamServices) extends ModeChoiceCalculator {

  override def clone(): ModeChoiceCalculator = new ModeChoiceTransitIfAvailable(beamServices)

  override def apply(alternatives: Vector[EmbodiedBeamTrip], choiceAttributes: Option[ChoiceAttributes]) = {
    var containsTransitAlt: Vector[Int] = Vector[Int]()
    alternatives.zipWithIndex.foreach { alt =>
      if (alt._1.tripClassifier.isTransit) {
        containsTransitAlt = containsTransitAlt :+ alt._2
      }
    }
    (if (containsTransitAlt.nonEmpty) {
      Some(containsTransitAlt.head)
    }
    else {
      chooseRandomAlternativeIndex(alternatives)
    }).map(x => alternatives(x))
  }

}
