package beam.agentsim.agents.modalBehaviors

import beam.router.RoutingModel.EmbodiedBeamTrip
import beam.sim.{BeamServices, HasServices}
import beam.agentsim.agents.choice.mode._

/**
  * BEAM
  */
trait ModeChoiceCalculator extends HasServices{
  def apply(alternatives: Vector[EmbodiedBeamTrip]): Option[EmbodiedBeamTrip]
}

object ModeChoiceCalculator {
  def apply(classname: String, beamServices: BeamServices): ModeChoiceCalculator = {
    classname match {
      case "ModeChoiceTransitOnly" =>
        new ModeChoiceTransitOnly(beamServices)
      case "ModeChoiceDriveOnly" =>
        new ModeChoiceDriveOnly(beamServices)
      case "ModeChoiceRideHailOnly" =>
        new ModeChoiceRideHailOnly(beamServices)
      case "ModeChoiceTransitIfAvailable" =>
        new ModeChoiceTransitIfAvailable(beamServices)
      case "ModeChoiceDriveIfAvailable" =>
        new ModeChoiceDriveIfAvailable(beamServices)
      case "ModeChoiceRideHailIfAvailable" =>
        new ModeChoiceRideHailIfAvailable(beamServices)
      case "ModeChoiceUniformRandom" =>
        new ModeChoiceUniformRandom(beamServices)
      case "ModeChoiceMultinomialLogit" =>
        new ModeChoiceMultinomialLogit(beamServices)
    }
  }
}
