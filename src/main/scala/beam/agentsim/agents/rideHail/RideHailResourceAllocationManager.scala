package beam.agentsim.agents.rideHail

import beam.agentsim.agents.rideHail.RideHailingManager.RideHailingInquiry
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.Location
import beam.router.RoutingModel.BeamTime
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

trait RideHailResourceAllocationManager {

  // TODO Asif: change parameters to case class VehicleAllocationRequest
  def getVehicleAllocation( pickUpLocation: Location, departAt: BeamTime, destination: Location): Option[VehicleAllocationResult]

  def allocateBatchRequest(allocationBatchRequest: Map[Id[RideHailingInquiry],VehicleAllocationRequest]): Map[Id[RideHailingInquiry],VehicleAllocationResult]

}


object RideHailResourceAllocationManager{
  val DEFAULT_MANAGER="DEFAULT_RIDEHAIL_ALLOCATION_MANAGER"
  val STANFORD_ALLOCATION_MANAGER_V1="STANFORD_RIDEHAIL_ALLOCATION_MANAGER_V1"
}

case class VehicleAllocation(vehicleId: Id[Vehicle],availableAt: SpaceTime)

case class VehicleAllocationResult(vehicleAllocation: Option[VehicleAllocation])

case class VehicleAllocationRequest(pickUpLocation: Location, departAt: BeamTime, destination: Location)

// TODO (RW): mention to CS that cost removed from VehicleAllocationResult, as not needed to be returned (RHM default implementation calculates it already)