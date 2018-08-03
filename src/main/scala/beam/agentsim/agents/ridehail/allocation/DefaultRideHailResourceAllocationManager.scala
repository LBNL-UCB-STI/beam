package beam.agentsim.agents.ridehail.allocation

import scala.collection.mutable
import beam.router.BeamRouter.Location
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

class DefaultRideHailResourceAllocationManager extends RideHailResourceAllocationManager {

  val bufferedRideHailRequests = new mutable.Queue[VehicleAllocationRequest]

  def proposeVehicleAllocation(
    vehicleAllocationRequest: VehicleAllocationRequest
  ): Option[VehicleAllocation] = {

    bufferedRideHailRequests += vehicleAllocationRequest

    None
  }

  override def repositionVehicles(tick: Double): Vector[(Id[Vehicle], Location)] = {
    Vector()
  }
}
