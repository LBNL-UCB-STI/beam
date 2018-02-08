package beam.agentsim.agents.choice.mode

import beam.agentsim.agents.modalBehaviors.ModeChoiceCalculator
import beam.router.Modes.BeamMode.CAR
import beam.router.RoutingModel.EmbodiedBeamTrip
import beam.sim.BeamServices

/**
  * BEAM
  */
class ModeChoiceDriveIfAvailable(val beamServices: BeamServices) extends ModeChoiceCalculator {

  def apply(alternatives: Seq[EmbodiedBeamTrip]): EmbodiedBeamTrip = {
    var containsDriveAlt: Vector[Int] = Vector[Int]()
    alternatives.zipWithIndex.foreach { alt =>
      if (alt._1.tripClassifier == CAR) {
        containsDriveAlt = containsDriveAlt :+ alt._2
      }
    }

    alternatives(if (containsDriveAlt.nonEmpty) {
      containsDriveAlt.head
    }
    else {
      chooseRandomAlternativeIndex(alternatives)
    })

  }

  override def utilityOf(alternative: EmbodiedBeamTrip): Double = 0.0

}
