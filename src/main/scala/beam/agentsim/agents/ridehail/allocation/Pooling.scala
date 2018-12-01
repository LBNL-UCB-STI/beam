package beam.agentsim.agents.ridehail.allocation

import beam.agentsim.agents.ridehail.RideHailManager.RideHailAgentLocation
import beam.agentsim.agents.ridehail.{RideHailManager, RideHailRequest}
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.{RoutingRequest, RoutingResponse}
import beam.router.Modes.BeamMode.CAR
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

class Pooling(val rideHailManager: RideHailManager) extends RideHailResourceAllocationManager(rideHailManager) {
  override def allocateVehiclesToCustomers(
                                            tick: Int,
                                            vehicleAllocationRequest: AllocationRequests
                                          ): AllocationResponse = {
    logger.info(s"buffer size: ${vehicleAllocationRequest.requests.size}")
    var toPool: Set[RideHailRequest] = Set()
    var notToPool: Set[RideHailRequest] = Set()
    var allocResponses: List[VehicleAllocation] = List()
    var alreadyAllocated: Set[Id[Vehicle]] = Set()
    vehicleAllocationRequest.requests.foreach {
      case (request, routingResponses) if routingResponses.isEmpty =>
        toPool += request
      case (request, _) =>
        notToPool += request
    }
    toPool.sliding(2).foreach { twoToPool =>
      twoToPool.size match {
        case 1 =>
          val request = twoToPool.head
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
              allocResponses = allocResponses :+ RoutingRequiredToAllocateVehicle(
                request,
                rideHailManager.createRoutingRequestsToCustomerAndDestination(
                  tick,
                  request,
                  agentETA.agentLocation
                )
              )
            case None =>
              allocResponses = allocResponses :+ NoVehicleAllocated(request)
          }
        case 2 =>
          val request1 = twoToPool.head
          val routingResponses1 = vehicleAllocationRequest.requests(request1)
          val request2 = twoToPool.last
          val routingResponses2 = vehicleAllocationRequest.requests(request2)
          rideHailManager
            .getClosestIdleVehiclesWithinRadiusByETA(
              request1.pickUpLocation,
              rideHailManager.radiusInMeters,
              tick,
              excludeRideHailVehicles = alreadyAllocated
            )
            .headOption match {
            case Some(agentETA) =>
              alreadyAllocated = alreadyAllocated + agentETA.agentLocation.vehicleId
              allocResponses = allocResponses :+ RoutingRequiredToAllocateVehicle(
                request1.copy(groupedWithOtherRequests = List(request2)),
                createRoutingRequestsForPooledTrip(List(request1, request2),
                  agentETA.agentLocation
                )
              )
            case None =>
              allocResponses = allocResponses :+ NoVehicleAllocated(request1)
              allocResponses = allocResponses :+ NoVehicleAllocated(request2)
          }
      }
    }
    if(allocResponses.size>0){
      val i = 0
    }
    VehicleAllocations(allocResponses)
  }

  def createRoutingRequestsForPooledTrip(requests: List[RideHailRequest], rideHailLocation: RideHailAgentLocation): List[RoutingRequest] = {
    var routeReqs: List[RoutingRequest] = List()
    var startTime = requests.head.departAt
    var rideHailVehicleAtOrigin = StreetVehicle(
      rideHailLocation.vehicleId,
      SpaceTime((rideHailLocation.currentLocation.loc, startTime)),
      CAR,
      asDriver = false
    )

    // Pickups first
    requests.foreach{ req =>
      val routeReq2Pickup = RoutingRequest(
        rideHailVehicleAtOrigin.location.loc,
        req.pickUpLocation,
        startTime,
        Vector(),
        Vector(rideHailVehicleAtOrigin)
      )
      routeReqs = routeReqs :+ routeReq2Pickup

      rideHailVehicleAtOrigin = StreetVehicle(rideHailLocation.vehicleId, SpaceTime((req.pickUpLocation, startTime)), CAR, asDriver = false)
    }

    // Dropoffs next
    requests.foreach{ req =>
      val routeReq2Dropoff = RoutingRequest(
        rideHailVehicleAtOrigin.location.loc,
        req.destination,
        startTime,
        Vector(),
        Vector(rideHailVehicleAtOrigin)
      )
      routeReqs = routeReqs :+ routeReq2Dropoff

      rideHailVehicleAtOrigin = StreetVehicle(rideHailLocation.vehicleId, SpaceTime((req.destination, startTime)), CAR, asDriver = false)
    }

    routeReqs
  }
//
//    // route from customer to destination
//    val rideHail2Destination = RoutingRequest(
//      request.pickUpLocation,
//      request.destination,
//      requestTime,
//      Vector(),
//      Vector(rideHailVehicleAtPickup)
//    )
//
//    List(rideHailAgent2Customer, rideHail2Destination)
//  }


}
