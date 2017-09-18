package beam.agentsim.agents.choice.mode

import beam.agentsim.agents.modalBehaviors.ModeChoiceCalculator
import beam.router.Modes.BeamMode.RIDEHAIL
import beam.router.RoutingModel.EmbodiedBeamTrip
import beam.sim.BeamServices

/**
  * BEAM
  */
class ModeChoiceRideHailIfAvailable(val beamServices: BeamServices) extends ModeChoiceCalculator {

  override def apply(alternatives: Vector[EmbodiedBeamTrip]) = {
      var containsDriveAlt: Vector[Int] = Vector[Int]()
      alternatives.zipWithIndex.foreach{ alt =>
      if(alt._1.tripClassifier == RIDEHAIL){
      containsDriveAlt = containsDriveAlt :+ alt._2
    }
    }
      val chosenIndex = if (containsDriveAlt.size > 0){ containsDriveAlt.head }else{ 0 }
      if(alternatives.size > 0) {
      Some(alternatives(chosenIndex))
    } else {
      None
    }
  }

}
