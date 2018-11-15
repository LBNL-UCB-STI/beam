package beam.agentsim.agents.ridehail.allocation

import beam.agentsim.agents.ridehail.RideHailManager
import beam.agentsim.agents.ridehail.RideHailManager.PoolingInfo
import beam.router.model.RoutingModel.DiscreteTime

class Pooling(val rideHailManager: RideHailManager) extends RideHailResourceAllocationManager(rideHailManager) {

  override def allocateVehicleToCustomer(
    vehicleAllocationRequest: VehicleAllocationRequest
  ): VehicleAllocationResponse = {

    rideHailManager
      .getClosestIdleVehiclesWithinRadius(
        vehicleAllocationRequest.request.pickUpLocation,
        rideHailManager.radiusInMeters
      )
      .headOption match {
      case Some(agentLocation) =>
        VehicleAllocation(agentLocation, None, Some(PoolingInfo(1.2, 0.6)))
      case None =>
        NoVehicleAllocated
    } // for inquiry the default option is sent to allow selection - some other could be sent here as well
  }

  override def batchAllocateVehiclesToCustomers(tick: Int, triggerId: Long): Unit = {
    logger.info(s"buffer size: ${bufferedRideHailRequests.get.size}")
    if(bufferedRideHailRequests.get.size > 1){
      val i = 0

    }

  }
}
