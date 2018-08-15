package beam.agentsim.agents.ridehail.allocation

import beam.agentsim.agents.ridehail.RideHailManager
import beam.router.BeamRouter.Location
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle


/*
TODO: check all network api, if they can use them properly

TODO: vehicleAllocationRequest.isInquiry==false => we need to make that call and allow the overwrite!

TODO: changing previous allocation should work

TODO: repositioning vehicles should be able to get duty any time

 */
class StanfordRideHailAllocationManagerV1(val rideHailManager: RideHailManager)
    extends RideHailResourceAllocationManager(rideHailManager) {

  /*
  This method is used to provide an initial vehicle allocation proposal (vehicleAllocationRequest.isInquiry==true).
  If the customer accepts the proposal, a second call will be made with (vehicleAllocationRequest.isInquiry==false),
  which initiates the ridehail agent to pickup the customer.

  This assignment can be attempted to overwritten later when the allocateVehicles is called.
   */

  override def proposeVehicleAllocation(
    vehicleAllocationRequest: VehicleAllocationRequest
  ): Option[VehicleAllocation] = {
    val rideHailAgentLocation = rideHailManager.getClosestIdleRideHailAgent(
      vehicleAllocationRequest.pickUpLocation,
      rideHailManager.radiusInMeters
    )

    rideHailAgentLocation match {
      case Some(rideHailLocation) =>
        Some(VehicleAllocation(rideHailLocation.vehicleId, rideHailLocation.currentLocation))
      case None => None
    }
  }

  /*
    This method can be used to attempt an overwrite of previous vehicle allocation proposal (only possible, if passenger not already picked up).
    This method is called periodically, e.g. every 60 seconds.
   */

  /*
    This method is called periodically, e.g. every 60 seconds to reposition ride hailing vehicles, e.g. towards areas of higher demand
   */
  override def repositionVehicles(tick: Double): Vector[(Id[Vehicle], Location)] = {
    if (rideHailManager.getIdleVehicles.size >= 2) {
      val iter = rideHailManager.getIdleVehicles.iterator
      val (vehicleIdA, _) = iter.next()
      val (_, vehicleLocationB) = iter.next()
      Vector((vehicleIdA, vehicleLocationB.currentLocation.loc))
    } else {
      Vector()
    }
  }

}
