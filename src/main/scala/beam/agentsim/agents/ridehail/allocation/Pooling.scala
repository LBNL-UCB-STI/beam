package beam.agentsim.agents.ridehail.allocation

import beam.agentsim.agents.ridehail.{RideHailManager, RideHailRequest}
import beam.agentsim.agents.ridehail.RideHailManager.PoolingInfo
import beam.router.model.RoutingModel.DiscreteTime

import scala.collection.mutable

class Pooling(val rideHailManager: RideHailManager) extends RideHailResourceAllocationManager(rideHailManager) {

  override def allocateVehicleToCustomer(
    vehicleAllocationRequest: AllocationRequests
  ): AllocationResponses = {
    val request = vehicleAllocationRequest.requests.keys.head

    AllocationResponses(request,rideHailManager
      .getClosestIdleVehiclesWithinRadius(
        request.pickUpLocation,
        rideHailManager.radiusInMeters
      )
      .headOption match {
      case Some(agentLocation) =>
        VehicleAllocation(agentLocation, None, Some(PoolingInfo(1.2, 0.6)))
      case None =>
        NoVehicleAllocated
    })
  }

  override def batchAllocateVehiclesToCustomers(tick: Int, vehicleAllocationRequest: AllocationRequests
                                               ): AllocationResponses = {
    logger.info(s"buffer size: ${vehicleAllocationRequest.requests.size}")
    val allocResponses = vehicleAllocationRequest.requests.map{ case(request,routingResponses) =>
        if(routingResponses.isEmpty) {
          rideHailManager
              .getClosestIdleVehiclesWithinRadius(
                request.pickUpLocation,
                rideHailManager.radiusInMeters
              )
              .headOption match {
              case Some(agentLocation) =>
                val routeRequired = RoutingRequiredToAllocateVehicle(rideHailManager.createRoutingRequestsToCustomerAndDestination(
                  request,
                  agentLocation
                ))
                (request -> routeRequired)
              case None =>
                (request -> NoVehicleAllocated)
            }
        }else {
          rideHailManager
            .getClosestIdleVehiclesWithinRadius(
              request.pickUpLocation,
              rideHailManager.radiusInMeters
            )
            .headOption match {
            case Some(agentLocation) =>
              (request -> VehicleAllocation(agentLocation, Some(routingResponses), None))
            case None =>
              (request -> NoVehicleAllocated)
          }
        }
    }.toMap
    AllocationResponses(allocResponses)
  }
}
