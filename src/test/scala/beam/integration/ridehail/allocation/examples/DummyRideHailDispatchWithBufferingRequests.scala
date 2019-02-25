//package beam.integration.ridehail.allocation.examples
//
//import beam.agentsim.agents.ridehail.RideHailManager
//import beam.agentsim.agents.ridehail.allocation._
//import beam.router.model.RoutingModel.DiscreteTime
//import com.typesafe.scalalogging.LazyLogging
//
//class DummyRideHailDispatchWithBufferingRequests(val rideHailManager: RideHailManager)
//    extends RideHailResourceAllocationManager(rideHailManager)
//    with LazyLogging {
//
//  val enableDummyRidehailReplacement = true
//
//  override def allocateVehicleToCustomer(
//    vehicleAllocationRequest: AllocationRequests
//  ): AllocationResponse = {
//
//    if (rideHailManager.getPendingDummyRequests.size < 5) {
//      rideHailManager.assignDummyRidehail(vehicleAllocationRequest.requests)
//    }
//
//    rideHailManager
//      .getClosestIdleVehiclesWithinRadius(
//        vehicleAllocationRequest.requests.pickUpLocation,
//        rideHailManager.radiusInMeters
//      )
//      .headOption match {
//      case Some(agentLocation) =>
//        VehicleAllocation(agentLocation, None)
//      case None =>
//        NoVehicleAllocated
//    } // for inquiry the default option is sent to allow selection - some other could be sent here as well
//  }
//
//  override def batchAllocateVehiclesToCustomers(tick: Int, triggerId: Long): Unit = {
//
//    if (enableDummyRidehailReplacement) {
//
//      // TODO: test if any issue with mixing with updateVehicleAllocations/handleRideCancellationReply
//      //bufferedRideHailRequests.newTimeout(tick, triggerId)
//
//      for (request <- rideHailManager.getCompletedDummyRequests.values) {
//        rideHailManager
//          .getClosestIdleRideHailAgent(
//            request.pickUpLocation,
//            rideHailManager.radiusInMeters
//          ) match {
//
//          case Some(rhl) =>
//            val updatedRequest = request.copy(
//              departAt = DiscreteTime(tick.toInt)
//            )
//
//            rideHailManager.createRoutingRequestsToCustomerAndDestination(
//              updatedRequest,
//              rhl
//            )
//
//            logger.debug(
//              " new vehicle assigned:{}, tick: {}, person: {}",
//              rhl.vehicleId,
//              tick,
//              request.customer.personId
//            )
//
//            rideHailManager.removeDummyRequest(request)
//
//            bufferedRideHailRequests.registerVehicleAsReplacementVehicle(rhl.vehicleId)
//          case None =>
//        }
//      }
//    }
//  }
//}
