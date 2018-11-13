package beam.agentsim.agents.ridehail.allocation

import beam.agentsim.agents.ridehail.RideHailManager
import beam.agentsim.agents.ridehail.RideHailManager.PoolingInfo
import beam.router.model.RoutingModel.DiscreteTime

class Pooling(val rideHailManager: RideHailManager) extends RideHailResourceAllocationManager(rideHailManager) {

  override def proposeVehicleAllocation(
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

  override def updateVehicleAllocations(tick: Int, triggerId: Long): Unit = {

    for (request <- rideHailManager.getCompletedDummyRequests.values) {
      rideHailManager
        .getClosestIdleRideHailAgent(
          request.pickUpLocation,
          rideHailManager.radiusInMeters
        ) match {

        case Some(rhl) =>
          val updatedRequest = request.copy(
            departAt = DiscreteTime(tick.toInt)
          )

          rideHailManager.createRoutingRequestsToCustomerAndDestination(
            updatedRequest,
            rhl
          )

          logger.debug(
            " new vehicle assigned:{}, tick: {}, person: {}",
            rhl.vehicleId,
            tick,
            request.customer.personId
          )

          rideHailManager.removeDummyRequest(request)

          bufferedRideHailRequests.registerVehicleAsReplacementVehicle(rhl.vehicleId)
        case None =>
      }
    }
  }
}
