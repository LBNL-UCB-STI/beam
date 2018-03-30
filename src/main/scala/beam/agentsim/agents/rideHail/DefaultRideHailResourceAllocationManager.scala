package beam.agentsim.agents.rideHail

import beam.agentsim.agents.rideHail.RideHailingManager.RideHailingInquiry
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.Location
import beam.router.RoutingModel.BeamTime
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

class DefaultRideHailResourceAllocationManager extends RideHailResourceAllocationManager {

  def getVehicleAllocation( pickUpLocation: Location, departAt: BeamTime, destination: Location): Option[VehicleAllocationResult] = {
    None
  }


  def allocateBatchRequests(allocationBatchRequest: Map[Id[RideHailingInquiry],VehicleAllocationRequest]): Map[Id[RideHailingInquiry],VehicleAllocationResult] = ???


  // def getVehicleAllocation(requestLocation: SpaceTime): VehicleAllocation = {

 //   ???
 // }
}


