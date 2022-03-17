package beam.agentsim.agents.planning

import beam.router.Modes.BeamMode

/**
  * BEAM
  */
object Strategy {

  sealed trait Strategy {
    def strategyMode: Option[BeamMode] = None
  }

  case class ModeChoiceStrategy(mode: Option[BeamMode]) extends Strategy {
    override def strategyMode: Option[BeamMode] = mode
  }

}
