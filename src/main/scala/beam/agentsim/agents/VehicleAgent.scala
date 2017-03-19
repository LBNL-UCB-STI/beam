package beam.agentsim.agents

import beam.agentsim.agents.BeamAgent.BeamAgentData
import beam.agentsim.agents.VehicleAgent.VehicleData
import org.matsim.api.core.v01.Id


object VehicleAgent{
  object VehicleData{}

  case class VehicleData() extends BeamAgentData
}



/**
  *
  */
class VehicleAgent(override val id: Id[VehicleAgent], override val data: VehicleData) extends BeamAgent[VehicleData]{

}

