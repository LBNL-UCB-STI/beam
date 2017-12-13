package beam.agentsim.agents.planning

import beam.router.Modes.BeamMode

/**
  * BEAM
  */
object Startegy{
  sealed trait Strategy

  case class ModeChoiceStrategy(mode: BeamMode) extends Strategy
}
