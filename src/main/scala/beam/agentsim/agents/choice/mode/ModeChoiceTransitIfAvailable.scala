package beam.agentsim.agents.choice.mode

import beam.agentsim.agents.modalBehaviors.ModeChoiceCalculator
import beam.agentsim.agents.modalBehaviors.ModeChoiceCalculator.AttributesOfIndividual
import beam.router.RoutingModel.EmbodiedBeamTrip
import beam.sim.BeamServices

/**
  * BEAM
  */
class ModeChoiceTransitIfAvailable(val beamServices: BeamServices) extends ModeChoiceCalculator {

  override def clone(): ModeChoiceCalculator = new ModeChoiceTransitIfAvailable(beamServices)

  override def apply(alternatives: Seq[EmbodiedBeamTrip], choiceAttributes: Option[AttributesOfIndividual]) = {
    var containsTransitAlt: Vector[Int] = Vector[Int]()
    alternatives.zipWithIndex.foreach { alt =>
      if (alt._1.tripClassifier.isTransit) {
        containsTransitAlt = containsTransitAlt :+ alt._2
      }
    }
    alternatives(if (containsTransitAlt.nonEmpty) {
      containsTransitAlt.head
    }
    else {
      chooseRandomAlternativeIndex(alternatives)
    })
  }

}
