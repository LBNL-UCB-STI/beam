package beam.agentsim.agents.choice.mode

import beam.agentsim.agents.modalBehaviors.ModeChoiceCalculator
import beam.agentsim.agents.modalBehaviors.ModeChoiceCalculator.AttributesOfIndividual
import beam.router.Modes.BeamMode.RIDEHAIL
import beam.router.RoutingModel.EmbodiedBeamTrip
import beam.sim.BeamServices

/**
  * BEAM
  */
class ModeChoiceRideHailIfAvailable(val beamServices: BeamServices) extends ModeChoiceCalculator {

  override def clone(): ModeChoiceCalculator = new ModeChoiceRideHailIfAvailable(beamServices)

  override def apply(alternatives: Vector[EmbodiedBeamTrip], choiceAttributes: Option[AttributesOfIndividual]) = {
    var containsDriveAlt: Vector[Int] = Vector[Int]()
    alternatives.zipWithIndex.foreach { alt =>
      if (alt._1.tripClassifier == RIDEHAIL) {
        containsDriveAlt = containsDriveAlt :+ alt._2
      }
    }

    (if (containsDriveAlt.nonEmpty) {
      containsDriveAlt.headOption
    }
    else {
      chooseRandomAlternativeIndex(alternatives)
    }).map(x => alternatives(x))
  }


}
