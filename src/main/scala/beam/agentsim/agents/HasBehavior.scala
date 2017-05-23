package beam.agentsim.agents

import beam.agentsim.agents.BeamAgent.BeamAgentState
import beam.agentsim.agents.PersonAgent.PersonData

/**
  * BEAM
  */
trait Behavior extends BeamAgent[PersonData]{
  def registerBehaviors(behaviors: Map[BeamAgentState,StateFunction]): Map[BeamAgentState,StateFunction]
}
