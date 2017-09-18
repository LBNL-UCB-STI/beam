package beam.agentsim.agents.choice.mode

import beam.agentsim.agents.modalBehaviors.ModeChoiceCalculator
import beam.router.RoutingModel.EmbodiedBeamTrip
import beam.sim.BeamServices

/**
  * BEAM
  */
class ModeChoiceTransitIfAvailable (val beamServices: BeamServices) extends ModeChoiceCalculator {

  override def apply(alternatives: Vector[EmbodiedBeamTrip]) = {
    var containsTransitAlt: Vector[Int] = Vector[Int]()
    alternatives.zipWithIndex.foreach{ alt =>
      if(alt._1.tripClassifier.isTransit){
        containsTransitAlt = containsTransitAlt :+ alt._2
      }
    }
    val chosenIndex = if (containsTransitAlt.size > 0){ containsTransitAlt.head }else{ 0 }
    if(alternatives.size > 0) {
      Some(alternatives(chosenIndex))
    } else {
      None
    }
  }

}
