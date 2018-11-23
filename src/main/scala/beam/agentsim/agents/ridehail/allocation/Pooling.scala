package beam.agentsim.agents.ridehail.allocation

import beam.agentsim.agents.ridehail.{RideHailManager}

class Pooling(val rideHailManager: RideHailManager) extends RideHailResourceAllocationManager(rideHailManager) {

  override def allocateVehiclesToCustomers(
    tick: Int,
    vehicleAllocationRequest: AllocationRequests
  ): AllocationResponse = {
    logger.info(s"buffer size: ${vehicleAllocationRequest.requests.size}")
    val allocResponses = vehicleAllocationRequest.requests.map {
      case (request, routingResponses) =>
        if (routingResponses.isEmpty) {
          rideHailManager
            .getClosestIdleVehiclesWithinRadius(
              request.pickUpLocation,
              rideHailManager.radiusInMeters
            )
            .headOption match {
            case Some(agentLocation) =>
              //TODO how to mix RoutingRequired with VehicleAllocation???
              val routeRequired = RoutingRequiredToAllocateVehicle(
                request,
                rideHailManager.createRoutingRequestsToCustomerAndDestination(
                  request,
                  agentLocation
                )
              )
              routeRequired
            case None =>
              NoVehicleAllocated(request)
          }
        } else {
          rideHailManager
            .getClosestIdleVehiclesWithinRadius(
              request.pickUpLocation,
              rideHailManager.radiusInMeters
            )
            .headOption match {
            case Some(agentLocation) =>
              val pickDropIdAndLegs = List(
                PickDropIdAndLeg(request.customer.personId, routingResponses.head),
                PickDropIdAndLeg(request.customer.personId, routingResponses.last)
              )
              VehicleMatchedToCustomers(request, agentLocation, pickDropIdAndLegs)
            case None =>
              NoVehicleAllocated(request)
          }
        }
    }.toList
    VehicleAllocations(allocResponses)
  }
}
