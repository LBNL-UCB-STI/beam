package beam.integration.ridehail.allocation.examples

import beam.agentsim.agents.ridehail.RideHailManager
import beam.agentsim.agents.ridehail.allocation._
import beam.router.RoutingModel.DiscreteTime

class DummyRideHailDispatchWithBufferingRequests(val rideHailManager: RideHailManager)
    extends RideHailResourceAllocationManager(rideHailManager) {

  val enableDummyRidehailReplacement = true

  override def proposeVehicleAllocation(
    vehicleAllocationRequest: VehicleAllocationRequest
  ): VehicleAllocationResponse = {

    if (rideHailManager.getPendingDummyRequests.size < 5) {
      rideHailManager.assignDummyRidehail(vehicleAllocationRequest.request)
    }

    rideHailManager
      .getClosestIdleVehiclesWithinRadius(
        vehicleAllocationRequest.request.pickUpLocation,
        rideHailManager.radiusInMeters
      )
      .headOption match {
      case Some(agentLocation) =>
        VehicleAllocation(agentLocation, None)
      case None =>
        NoVehicleAllocated
    } // for inquiry the default option is sent to allow selection - some other could be sent here as well
  }

  override def updateVehicleAllocations(tick: Double, triggerId: Long): Unit = {

    if (enableDummyRidehailReplacement) {

      // TODO: test if any issue with mixing with updateVehicleAllocations/handleRideCancellationReply
      //bufferedRideHailRequests.newTimeout(tick, triggerId)

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

            println(
              s" new vehicle assigned:${rhl.vehicleId}, tick: ${tick}, person: ${request.customer.personId}"
            )

            rideHailManager.removeDummyRequest(request)

            bufferedRideHailRequests.registerVehicleAsReplacementVehicle(rhl.vehicleId)
          case None =>
        }
        //bufferedRideHailRequests.registerVehicleAsReplacementVehicle(rhl.vehicleId)

      }

    }

  }

//  override def handleRideCancellationReply(
  //   reply: DrivesVehicle.StopDrivingIfNoPassengerOnBoardReply
  // ): Unit = ???

}
