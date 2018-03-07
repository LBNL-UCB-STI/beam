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
    var containsDriveAlt: Vector[Int] = Vector[Int]()
    alternatives.zipWithIndex.foreach { alt =>
      if (alt._1.tripClassifier == RIDE_HAIL) {
        containsDriveAlt = containsDriveAlt :+ alt._2
      }
    }

    Some(alternatives(if (containsDriveAlt.nonEmpty) {
      containsDriveAlt.head
    }
    else {
      chooseRandomAlternativeIndex(alternatives)
    }))
  }

  override def utilityOf(alternative: EmbodiedBeamTrip): Double = 0.0

  override def utilityOf(mode: Modes.BeamMode, cost: Double, time: Double, numTransfers: Int): Double = 0.0
}
