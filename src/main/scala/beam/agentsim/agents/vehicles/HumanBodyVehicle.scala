package beam.agentsim.agents.vehicles

import beam.agentsim.agents.BeamAgent
import beam.agentsim.agents.PersonAgent.PersonData
import org.matsim.api.core.v01.Id

class HumanBodyVehicle(val id: Id[HumanBodyVehicle], val vehicleData: VehicleData, val powerTrain: Powertrain,
                       var driver: Option[BeamAgent[PersonData]] = None) extends BeamVehicle[HumanBodyVehicle] {
  //XXX: be careful with traversing,  possible recursion
  def passengers: List[BeamVehicle[_]] = List(this)

  def carrier = None

}

/**
  *
  * @param weight in kilo
  * @param height in kilo
  */
case class HumanDimension(weight: Double, height: Double) extends Dimension

