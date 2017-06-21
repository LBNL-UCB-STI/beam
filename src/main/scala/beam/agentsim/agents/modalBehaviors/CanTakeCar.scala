package beam.agentsim.agents.modalBehaviors

import beam.agentsim.agents.{BeamAgent, PersonAgent, TriggerShortcuts}
import beam.agentsim.agents.PersonAgent.{ChoosingMode, PersonData}
import beam.sim.HasServices

/**
  * BEAM
  */
trait CanTakeCar extends BeamAgent[PersonData] with TriggerShortcuts with HasServices{
  this: PersonAgent => // Self type restricts this trait to only mix into a PersonAgent

  onTransition{
    case _ -> ChoosingMode =>
      logInfo(s"entering to ChoosingMode")
  }
}
