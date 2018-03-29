package beam.agentsim.agents.rideHail

import beam.agentsim.events.SpaceTime

trait RideHailResourceAllocationManager {

  def getVehicleAllocation(requestLocation: SpaceTime): VehicleAllocationResult

}


object RideHailResourceAllocationManager{
  val DEFAULT_MANAGER="DEFAULT_RIDEHAIL_ALLOCATION_MANAGER"
  val STANFORD_ALLOCATION_MANAGER_V1="STANFORD_RIDEHAIL_ALLOCATION_MANAGER_V1"
}
