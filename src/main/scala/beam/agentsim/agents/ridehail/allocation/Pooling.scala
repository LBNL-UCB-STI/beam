package beam.agentsim.agents.ridehail.allocation

import beam.agentsim.agents.ridehail.RideHailManager
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

class Pooling(val rideHailManager: RideHailManager) extends RideHailResourceAllocationManager(rideHailManager) {

  override def allocateVehiclesToCustomers(
    tick: Int,
    vehicleAllocationRequest: AllocationRequests
  ): AllocationResponse = {
    logger.info(s"buffer size: ${vehicleAllocationRequest.requests.size}")
    var alreadyAllocated: Set[Id[Vehicle]] = Set()
    val allocResponses = vehicleAllocationRequest.requests.map {
      case (request, routingResponses) if (routingResponses.isEmpty) =>
        rideHailManager
          .getClosestIdleVehiclesWithinRadiusByETA(
            request.pickUpLocation,
            rideHailManager.radiusInMeters,
            tick
          )
          .headOption match {
          case Some(agentETA) =>
            //TODO how to mix RoutingRequired with VehicleAllocation???
            val routeRequired = RoutingRequiredToAllocateVehicle(
              request,
              rideHailManager.createRoutingRequestsToCustomerAndDestination(
                tick,
                request,
                agentETA.agentLocation
              )
            )
            routeRequired
          case None =>
            NoVehicleAllocated(request)
        }
      case (request, routingResponses) =>
        rideHailManager
          .getClosestIdleVehiclesWithinRadiusByETA(
            request.pickUpLocation,
            rideHailManager.radiusInMeters,
            tick,
            excludeRideHailVehicles = alreadyAllocated
          )
          .headOption match {
          case Some(agentETA) =>
            alreadyAllocated = alreadyAllocated + agentETA.agentLocation.vehicleId
            val pickDropIdAndLegs = List(
              PickDropIdAndLeg(request.customer.personId, routingResponses.head),
              PickDropIdAndLeg(request.customer.personId, routingResponses.last)
            )
            VehicleMatchedToCustomers(request, agentETA.agentLocation, pickDropIdAndLegs)
          case None =>
            NoVehicleAllocated(request)
        }
    }.toList
    VehicleAllocations(allocResponses)
  }
}
