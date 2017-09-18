package beam.agentsim.agents.choice.mode

import beam.agentsim.agents.modalBehaviors.ModeChoiceCalculator
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{CAR, RIDEHAIL, TRANSIT}
import beam.router.RoutingModel.EmbodiedBeamTrip
import beam.sim.BeamServices

import scala.util.Random

/**
  * BEAM
  */
class ModeChoiceUniformRandom(val beamServices: BeamServices) extends ModeChoiceCalculator {

  override def apply(alternatives: Vector[EmbodiedBeamTrip]) = {
    Some(Random.shuffle(alternatives.toList).head)
  }

}
