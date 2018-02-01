package beam.agentsim.agents.choice.mode

import beam.agentsim.agents.household.HouseholdActor.AttributesOfIndividual
import beam.agentsim.agents.modalBehaviors.ModeChoiceCalculator
import beam.router.Modes.BeamMode.CAR
import beam.router.RoutingModel.EmbodiedBeamTrip
import beam.sim.BeamServices

/**
  * BEAM
  */
class ModeChoiceDriveIfAvailable(val beamServices: BeamServices) extends ModeChoiceCalculator {

  override def clone(): ModeChoiceCalculator = new ModeChoiceDriveIfAvailable(beamServices)

  def apply(alternatives: Seq[EmbodiedBeamTrip], choiceAttributes: Option[AttributesOfIndividual]): EmbodiedBeamTrip = {
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
}
