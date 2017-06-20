package beam.agentsim.agents

import akka.actor.FSM
import beam.agentsim.agents.BeamAgent.{BeamAgentInfo, BeamAgentState}
import beam.agentsim.agents.PersonAgent.PersonData

/**
  * BEAM
  */
object ComposedStateFunction{
  def apply(partialFunction: PartialFunction[FSM.Event[_] ,FSM.State[BeamAgentState,BeamAgentInfo[PersonData]]]) =
    new ComposedStateFunction(partialFunction)
}
class ComposedStateFunction(partialFunction: PartialFunction[FSM.Event[_] ,FSM.State[BeamAgentState,BeamAgentInfo[PersonData]]]) {
  var pf = partialFunction

  def add(partialFunction: PartialFunction[FSM.Event[_] ,FSM.State[BeamAgentState,BeamAgentInfo[PersonData]]]): Unit ={
    pf = pf orElse partialFunction
  }
}
