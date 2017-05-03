package beam.agentsim.agents.vehicles

import beam.agentsim.agents.BeamAgent
import beam.agentsim.agents.BeamAgent.BeamAgentData
import beam.agentsim.agents.vehicles.VehicleAgent.VehicleData
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

/*
driver is a BeamAgent, need not be physically inside vehicle
passengers are BeamVehicles and are physically in vehicle and move with the vehicle (e.g. a PersonAgent who is driving alone would be the driver of the vehicle and his/her HumanBodyVehicle would be a passenger)
HumanBody is a special case, it has no passengers, just a driver

BeamVeh fields
enclosingVehicle: Option[BeamVehicle] (None if top of nest)
driver: Option[BeamAgent]
passengers: List[BeamVehicles]
defaultLocationanager: LocationManger
getLocationManager: enclosingVehicle match {
Some[enclosing] -> delegate to enclosing
None -> defaultLocationManager
powertrain: PowerTrain


LocationMan
agent: BeamAgent
location: Coord


 */