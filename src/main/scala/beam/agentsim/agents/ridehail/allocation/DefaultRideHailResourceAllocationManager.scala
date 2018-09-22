package beam.agentsim.agents.ridehail.allocation

import beam.agentsim.agents.ridehail.RideHailManager

class DefaultRideHailResourceAllocationManager(val rideHailManager: RideHailManager)
    extends RideHailResourceAllocationManager(rideHailManager) {

  // Only override proposeVehicleAllocation if you wish to do something different from closest euclidean vehicle
  //  override def proposeVehicleAllocation(vehicleAllocationRequest: VehicleAllocationRequest): VehicleAllocationResponse

}
