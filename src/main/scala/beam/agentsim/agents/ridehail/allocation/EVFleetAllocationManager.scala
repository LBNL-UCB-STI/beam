package beam.agentsim.agents.rideHail.allocation

import beam.agentsim.agents.ridehail.allocation._
import beam.agentsim.agents.ridehail.{ReserveRide, RideHailManager}
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle
import java.util.UUID

class EVFleetAllocationManager(val rideHailManager: RideHailManager)
    extends RideHailResourceAllocationManager(rideHailManager) {

  val dummyDriverId = Id.create("NA", classOf[Vehicle])
  val routeReqToDriverMap = scala.collection.mutable.Map[UUID, Id[Vehicle]]()

  override def proposeVehicleAllocation(
    vehicleAllocationRequest: VehicleAllocationRequest
  ): VehicleAllocationResponse = {

    // Update local storage of computed routes by driver
    val routeResponsesByDriver = vehicleAllocationRequest.routingResponses
      .groupBy { response =>
        routeReqToDriverMap.getOrElse(response.requestId.get, dummyDriverId)
      }

    // If no route responses, then we have no agents in mind
    var agentLocationOpt = if (routeResponsesByDriver.isEmpty) {
      None
    } else {
      // Otherwise, we look through all routes returned and find the first one that
      // corresponds to an idle driver and use this as our agentLocation
      val maybeId = routeResponsesByDriver.keys.find(rideHailManager.getIdleVehicles.contains(_))
      maybeId.map(rideHailManager.getIdleVehicles.get(_).get)
    }
    agentLocationOpt = agentLocationOpt match {
      case None =>
        // go with closest
        rideHailManager
          .getClosestIdleVehiclesWithinRadius(
            vehicleAllocationRequest.request.pickUpLocation,
            rideHailManager.radiusInMeters
          )
          .headOption
      case _ =>
        agentLocationOpt
    }

    agentLocationOpt match {
      case Some(agentLocation) if routeResponsesByDriver.contains(agentLocation.vehicleId) =>
        val routingResponses = routeResponsesByDriver.get(agentLocation.vehicleId)
        // Clean up internal tracking then send the allocation with the responses
        vehicleAllocationRequest.routingResponses.foreach { response =>
          if (routeReqToDriverMap.contains(response.requestId.get))
            routeReqToDriverMap.remove(response.requestId.get)
        }
        VehicleAllocation(agentLocation, routingResponses)
      case Some(agentLocation) =>
        val requests = rideHailManager.createRoutingRequestsToCustomerAndDestination(
          vehicleAllocationRequest.request,
          agentLocation
        )
        routeReqToDriverMap.put(requests.head.requestId, agentLocation.vehicleId)
        routeReqToDriverMap.put(requests.last.requestId, agentLocation.vehicleId)

        RoutingRequiredToAllocateVehicle(
          vehicleAllocationRequest.request,
          requests
        )
//            VehicleAllocation(agentLocation, None)
      case None =>
        NoVehicleAllocated
    }
  }

}
