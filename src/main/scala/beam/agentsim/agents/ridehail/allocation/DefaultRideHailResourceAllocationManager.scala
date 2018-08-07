package beam.agentsim.agents.ridehail.allocation

import beam.router.BeamRouter.Location
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

class DefaultRideHailResourceAllocationManager extends RideHailResourceAllocationManager {

  override val isBufferedRideHailAllocationMode = false

  override def proposeVehicleAllocation(
    vehicleAllocationRequest: VehicleAllocationRequest
  ): Option[VehicleAllocation] = {
    None
  }

  override def allocateVehicles(
    allocationsDuringReservation: Vector[(VehicleAllocationRequest, Option[VehicleAllocation])]
  ): IndexedSeq[(VehicleAllocationRequest, Option[VehicleAllocation])] = {
    throw new NotImplementedError("DefaultRideHailResourceAllocationManager.allocateVehicles not implemented.")
  }

  override def repositionVehicles(tick: Double): Vector[(Id[Vehicle], Location)] = {
    Vector()
  }
}
