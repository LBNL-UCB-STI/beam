package beam.agentsim.agents.ridehail.allocation

import scala.collection.mutable
import beam.router.BeamRouter.Location
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

class DefaultRideHailResourceAllocationManager
    extends RideHailResourceAllocationManager
    with HandlesDispatching {

  def proposeVehicleAllocation(
    vehicleAllocationRequest: VehicleAllocationRequest
  ): Option[VehicleAllocation] = {

    None
  }

}
