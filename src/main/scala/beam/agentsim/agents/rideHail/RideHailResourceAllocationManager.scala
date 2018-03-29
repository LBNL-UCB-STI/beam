package beam.agentsim.agents.rideHail

import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.Location
import beam.router.RoutingModel.BeamTime
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

trait RideHailResourceAllocationManager {

  def getVehicleAllocation( pickUpLocation: Location, departAt: BeamTime, destination: Location): Option[VehicleAllocationResult]

}


object RideHailResourceAllocationManager{
  val DEFAULT_MANAGER="DEFAULT_RIDEHAIL_ALLOCATION_MANAGER"
  val STANFORD_ALLOCATION_MANAGER_V1="STANFORD_RIDEHAIL_ALLOCATION_MANAGER_V1"
}

case class VehicleAllocation(vehicleId: Id[Vehicle],availableAt: SpaceTime)

case class VehicleAllocationResult(vehicleAllocation: Option[VehicleAllocation], cost: Double)