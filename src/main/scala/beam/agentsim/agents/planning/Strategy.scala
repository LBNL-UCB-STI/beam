package beam.agentsim.agents.planning

import beam.router.Modes.BeamMode

/**
  * BEAM
  */
object Strategy {

  trait Strategy {
    def tripStrategies(tour: Tour): Seq[(Trip, Strategy)] = Seq.empty
  }

  case class ModeChoiceStrategy(mode: Option[BeamMode]) extends Strategy

}
