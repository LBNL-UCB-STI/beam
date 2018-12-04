package beam.agentsim.agents.ridehail.allocation

import beam.agentsim.agents.ridehail.RideHailManager.{PoolingInfo, RideHailAgentLocation}
import beam.agentsim.agents.ridehail.{RideHailManager, RideHailRequest}
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.{RoutingRequest, RoutingResponse}
import beam.router.Modes.BeamMode.CAR
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

import scala.collection.mutable

class Pooling(val rideHailManager: RideHailManager) extends RideHailResourceAllocationManager(rideHailManager) {

  val tempPickDropStore: mutable.Map[Int, PickDropIdAndLeg] = mutable.Map()

  override def respondToInquiry(inquiry: RideHailRequest): InquiryResponse = {
    rideHailManager
      .getClosestIdleVehiclesWithinRadiusByETA(
        inquiry.pickUpLocation,
        rideHailManager.radiusInMeters,
        inquiry.departAt
      )
      .headOption match {
      case Some(agentETA) =>
        SingleOccupantQuoteAndPoolingInfo(agentETA.agentLocation, None, Some(PoolingInfo(1.1, 0.6)))
      case None =>
        NoVehiclesAvailable
    }
  }

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
    notToPool.foreach { request =>
      val routeResponses = vehicleAllocationRequest.requests(request)

      // First check for broken route responses (failed routing attempt)
      if (routeResponses.find(_.itineraries.size == 0).isDefined) {
        allocResponses = allocResponses :+ NoVehicleAllocated(request)
      } else {
        // Make sure vehicle still available
        val vehicleId = routeResponses.head.itineraries.head.legs.head.beamVehicleId
        if (rideHailManager.getIdleVehicles.contains(vehicleId) && !alreadyAllocated.contains(vehicleId)) {
          alreadyAllocated = alreadyAllocated + vehicleId
          val pickDropIdAndLegs = routeResponses.map { rResp =>
            tempPickDropStore
              .remove(rResp.staticRequestId)
              .getOrElse(PickDropIdAndLeg(request.customer, None))
              .copy(leg = rResp.itineraries.head.legs.headOption)
          }
          if (routeResponses.size > 2) {
            val i = 0
          }
          allocResponses = allocResponses :+ VehicleMatchedToCustomers(
            request,
            rideHailManager.getIdleVehicles(vehicleId),
            pickDropIdAndLegs
          )
        } else {
          allocResponses = allocResponses :+ NoVehicleAllocated(request)
          request.groupedWithOtherRequests.foreach { req =>
            allocResponses = allocResponses :+ NoVehicleAllocated(req)
          }
        }
      }
    }
    toPool.grouped(2).foreach { twoToPool =>
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
                request1.addSubRequest(request2),
                createRoutingRequestsForPooledTrip(List(request1, request2), agentETA.agentLocation, tick)
              )
              // When we group request 2 with 1 we need to remove it from the buffer
              // so it won't be processed again (it's fate is now tied to request 1)
              removeRequestFromBuffer(request2)
            case None =>
              allocResponses = allocResponses :+ NoVehicleAllocated(request1)
              allocResponses = allocResponses :+ NoVehicleAllocated(request2)
          }
      }
    }
    VehicleAllocations(allocResponses)
  }

  def createRoutingRequestsForPooledTrip(
    requests: List[RideHailRequest],
    rideHailLocation: RideHailAgentLocation,
    tick: Int
  ): List[RoutingRequest] = {
    var routeReqs: List[RoutingRequest] = List()
    var startTime = tick
    var rideHailVehicleAtOrigin = StreetVehicle(
      rideHailLocation.vehicleId,
      SpaceTime((rideHailLocation.currentLocation.loc, startTime)),
      CAR,
      asDriver = false
    )

    // Pickups first
    requests.foreach { req =>
      val routeReq2Pickup = RoutingRequest(
        rideHailVehicleAtOrigin.location.loc,
        req.pickUpLocation,
        startTime,
        Vector(),
        Vector(rideHailVehicleAtOrigin)
      )
      routeReqs = routeReqs :+ routeReq2Pickup
      tempPickDropStore.put(routeReq2Pickup.staticRequestId, PickDropIdAndLeg(req.customer, None))

      rideHailVehicleAtOrigin =
        StreetVehicle(rideHailLocation.vehicleId, SpaceTime((req.pickUpLocation, startTime)), CAR, asDriver = false)
    }

    // Dropoffs next
    requests.foreach { req =>
      val routeReq2Dropoff = RoutingRequest(
        rideHailVehicleAtOrigin.location.loc,
        req.destination,
        startTime,
        Vector(),
        Vector(rideHailVehicleAtOrigin)
      )
      routeReqs = routeReqs :+ routeReq2Dropoff
      tempPickDropStore.put(routeReq2Dropoff.staticRequestId, PickDropIdAndLeg(req.customer, None))

      rideHailVehicleAtOrigin =
        StreetVehicle(rideHailLocation.vehicleId, SpaceTime((req.destination, startTime)), CAR, asDriver = false)
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
