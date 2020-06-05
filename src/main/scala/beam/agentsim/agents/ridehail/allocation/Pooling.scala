package beam.agentsim.agents.ridehail.allocation

import beam.agentsim.agents.{Dropoff, MobilityRequest, Pickup}
import beam.agentsim.agents.ridehail.RideHailManager.PoolingInfo
import beam.agentsim.agents.ridehail.RideHailVehicleManager.RideHailAgentLocation
import beam.agentsim.agents.ridehail.{RideHailManager, RideHailRequest}
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.RoutingRequest
import beam.router.Modes.BeamMode.CAR
import beam.sim.BeamServices
import org.matsim.api.core.v01.Id

import scala.collection.mutable

class Pooling(val rideHailManager: RideHailManager) extends RideHailResourceAllocationManager(rideHailManager) {

  val tempPickDropStore: mutable.Map[Int, MobilityRequest] = mutable.Map()

  override def respondToInquiry(inquiry: RideHailRequest): InquiryResponse = {
    rideHailManager.vehicleManager
      .getClosestIdleVehiclesWithinRadiusByETA(
        inquiry.pickUpLocationUTM,
        inquiry.destinationUTM,
        rideHailManager.radiusInMeters,
        inquiry.departAt
      ) match {
      case Some(agentETA) =>
        SingleOccupantQuoteAndPoolingInfo(agentETA.agentLocation, Some(PoolingInfo(1.1, 0.6)))
      case None =>
        NoVehiclesAvailable
    }
  }

  override def allocateVehiclesToCustomers(
    tick: Int,
    vehicleAllocationRequest: AllocationRequests,
    beamServices: BeamServices
  ): AllocationResponse = {
    logger.info(s"buffer size: ${vehicleAllocationRequest.requests.size}")
    var toPool: Set[RideHailRequest] = Set()
    var notToPool: Set[RideHailRequest] = Set()
    var allocResponses: Vector[VehicleAllocation] = Vector()
    var alreadyAllocated: Set[Id[BeamVehicle]] = Set()
    vehicleAllocationRequest.requests.foreach {
      case (request, routingResponses) if routingResponses.isEmpty =>
        toPool += request
      case (request, _) =>
        notToPool += request
    }
    val idleButFilteredVehicles = rideHailManager.vehicleManager.getIdleVehiclesAndFilterOutExluded
    notToPool.foreach { request =>
      val routeResponses = vehicleAllocationRequest.requests(request)

      // First check for broken route responses (failed routing attempt)
      if (routeResponses.find(_.itineraries.isEmpty).isDefined) {
        allocResponses = allocResponses :+ NoVehicleAllocated(request)
      } else {
        // Make sure vehicle still available
        val vehicleId = routeResponses.head.itineraries.head.legs.head.beamVehicleId
        if (idleButFilteredVehicles.contains(vehicleId) && !alreadyAllocated.contains(vehicleId)) {
          alreadyAllocated = alreadyAllocated + vehicleId
          val pickDropIdAndLegs = routeResponses.map { rResp =>
            tempPickDropStore
              .remove(rResp.requestId)
              .getOrElse(
                MobilityRequest.simpleRequest(Pickup, Some(request.customer), rResp.itineraries.head.legs.headOption)
              )
          }
          allocResponses = allocResponses :+ VehicleMatchedToCustomers(
            request,
            idleButFilteredVehicles(vehicleId),
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
          Pooling.serveOneRequest(twoToPool.head, tick, alreadyAllocated, rideHailManager, beamServices) match {
            case res @ RoutingRequiredToAllocateVehicle(_, routes) =>
              allocResponses = allocResponses :+ res
              alreadyAllocated = alreadyAllocated + routes.head.streetVehicles.head.id
            case res =>
              allocResponses = allocResponses :+ res
          }
        case 2 =>
          val request1 = twoToPool.head
          val request2 = twoToPool.last
          val request1Updated = RideHailRequest.handleImpression(request1, beamServices)
          val request2Updated = RideHailRequest.handleImpression(request2, beamServices)
          val routingResponses1 = vehicleAllocationRequest.requests(request1Updated)
          val routingResponses2 = vehicleAllocationRequest.requests(request2Updated)
          rideHailManager.vehicleManager
            .getClosestIdleVehiclesWithinRadiusByETA(
              request1Updated.pickUpLocationUTM,
              request1Updated.destinationUTM,
              rideHailManager.radiusInMeters,
              tick,
              excludeRideHailVehicles = alreadyAllocated
            ) match {
            case Some(agentETA) =>
              alreadyAllocated = alreadyAllocated + agentETA.agentLocation.vehicleId
              allocResponses = allocResponses :+ RoutingRequiredToAllocateVehicle(
                request1Updated.addSubRequest(request2Updated),
                createRoutingRequestsForPooledTrip(List(request1Updated, request2Updated), agentETA.agentLocation, tick)
              )
              // When we group request 2 with 1 we need to remove it from the buffer
              // so it won't be processed again (it's fate is now tied to request 1)
              removeRequestFromBuffer(request2Updated)
            case None =>
              allocResponses = allocResponses :+ NoVehicleAllocated(request1Updated)
              allocResponses = allocResponses :+ NoVehicleAllocated(request2Updated)
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
      rideHailLocation.vehicleType.id,
      SpaceTime((rideHailLocation.currentLocationUTM.loc, startTime)),
      CAR,
      asDriver = false
    )

    // Pickups first
    requests.foreach { req =>
      val routeReq2Pickup = RoutingRequest(
        rideHailVehicleAtOrigin.locationUTM.loc,
        req.pickUpLocationUTM,
        startTime,
        withTransit = false,
        Vector(rideHailVehicleAtOrigin)
      )
      routeReqs = routeReqs :+ routeReq2Pickup
      tempPickDropStore.put(routeReq2Pickup.requestId, MobilityRequest.simpleRequest(Pickup, Some(req.customer), None))

      rideHailVehicleAtOrigin = StreetVehicle(
        rideHailLocation.vehicleId,
        rideHailLocation.vehicleType.id,
        SpaceTime((req.pickUpLocationUTM, startTime)),
        CAR,
        asDriver = false
      )
    }

    // Dropoffs next
    requests.foreach { req =>
      val routeReq2Dropoff = RoutingRequest(
        rideHailVehicleAtOrigin.locationUTM.loc,
        req.destinationUTM,
        startTime,
        withTransit = false,
        Vector(rideHailVehicleAtOrigin)
      )
      routeReqs = routeReqs :+ routeReq2Dropoff
      tempPickDropStore.put(
        routeReq2Dropoff.requestId,
        MobilityRequest.simpleRequest(Dropoff, Some(req.customer), None)
      )

      rideHailVehicleAtOrigin = StreetVehicle(
        rideHailLocation.vehicleId,
        rideHailLocation.vehicleType.id,
        SpaceTime((req.destinationUTM, startTime)),
        CAR,
        asDriver = false
      )
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

object Pooling {

  def serveOneRequest(
    request: RideHailRequest,
    pickUpTime: Int,
    alreadyAllocated: Set[Id[BeamVehicle]],
    rideHailManager: RideHailManager,
    beamServices: BeamServices
  ) = {
    val requestUpdated = RideHailRequest.handleImpression(request, beamServices)
    rideHailManager.vehicleManager
      .getClosestIdleVehiclesWithinRadiusByETA(
        requestUpdated.pickUpLocationUTM,
        requestUpdated.destinationUTM,
        rideHailManager.radiusInMeters,
        pickUpTime,
        excludeRideHailVehicles = alreadyAllocated
      ) match {
      case Some(agentETA) =>
        RoutingRequiredToAllocateVehicle(
          requestUpdated,
          rideHailManager.createRoutingRequestsToCustomerAndDestination(
            pickUpTime,
            requestUpdated,
            agentETA.agentLocation
          )
        )
      case None =>
        NoVehicleAllocated(requestUpdated)
    }
  }

}
