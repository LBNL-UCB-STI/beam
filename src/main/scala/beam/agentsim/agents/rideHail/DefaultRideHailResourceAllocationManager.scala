package beam.agentsim.agents.rideHail

import beam.agentsim.events.SpaceTime
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

class DefaultRideHailResourceAllocationManager extends RideHailResourceAllocationManager {

  def getVehicleAllocation(requestLocation: SpaceTime): VehicleAllocationResult = {

    ???
  }


 // def getVehicleAllocation(requestLocation: SpaceTime): VehicleAllocation = {

 //   ???
 // }
}


case class VehicleAllocation(vehicleId: Id[Vehicle],spaceTime: SpaceTime)

case class VehicleAllocationResult(vehicleId: Option[VehicleAllocation], cost: Option[Double])