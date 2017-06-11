package beam.agentsim.agents.vehicles

import beam.agentsim.agents.BeamAgent
import org.matsim.api.core.v01.Id

/**
  *
  * @author dserdiuk
  */
class SimpleBeamVehicle(val id: Id[SimpleBeamVehicle], val vehicleData: VehicleData, val powerTrain: Powertrain,
                        var carrier: Option[BeamVehicle[_]] = None,
                        var passengers: List[BeamVehicle[_]] = Nil,
                        var driver: Option[BeamAgent[_]] = None) extends BeamVehicle[SimpleBeamVehicle] {

  private var positionOnTrajectory = 0

  override def getId: Id[SimpleBeamVehicle] = id

  def moveNext() = {
    positionOnTrajectory = positionOnTrajectory + 1
  }
}


/**
  * Defines basic dimension of vehicle
  *
  * @param capacity number of passengers
  * @param length   length in meters
  */
case class VehicleDimension(capacity: Int, length: Double) extends Dimension
