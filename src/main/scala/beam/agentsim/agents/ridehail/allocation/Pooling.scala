package beam.agentsim.agents.ridehail.allocation

import beam.agentsim.agents.{Dropoff, MobilityRequest, Pickup}
import beam.agentsim.agents.ridehail.RideHailManager.PoolingInfo
import beam.agentsim.agents.ridehail.RideHailManagerHelper.RideHailAgentLocation
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
    rideHailManager.rideHailManagerHelper
      .getClosestIdleVehiclesWithinRadiusByETA(
        pickupLocation = inquiry.pickUpLocationUTM,
        dropOffLocation = inquiry.destinationUTM,
        radius = rideHailManager.radiusInMeters,
        customerRequestTime = inquiry.departAt,
        maxWaitingTimeInSec = maxWaitTimeInSec,
        includeRepositioningVehicles = true
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
    beamServices: BeamServices,
    triggerId: Long,
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
    val idleButFilteredVehicles = rideHailManager.rideHailManagerHelper.getIdleVehiclesAndFilterOutExluded
    notToPool.foreach { request =>
      val routeResponses = vehicleAllocationRequest.requests(request)

      // First check for broken route responses (failed routing attempt)
      if (routeResponses.exists(_.itineraries.isEmpty)) {
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
          Pooling.serveOneRequest(
            twoToPool.head,
            tick,
            alreadyAllocated,
            rideHailManager,
            beamServices,
            maxWaitTimeInSec
          ) match {
            case res @ RoutingRequiredToAllocateVehicle(_, routes) =>
              allocResponses = allocResponses :+ res
              alreadyAllocated = alreadyAllocated + routes.head.streetVehicles.head.id
            case res =>
              allocResponses = allocResponses :+ res
          }
        case 2 =>
          val request1 = twoToPool.head
          val request2 = twoToPool.last
          val request1Utm = RideHailRequest.projectCoordinatesToUtm(request1, beamServices)
          val request2Utm = RideHailRequest.projectCoordinatesToUtm(request2, beamServices)
          rideHailManager.rideHailManagerHelper
            .getClosestIdleVehiclesWithinRadiusByETA(
              pickupLocation = request1Utm.pickUpLocationUTM,
              dropOffLocation = request1Utm.destinationUTM,
              radius = rideHailManager.radiusInMeters,
              customerRequestTime = tick,
              maxWaitingTimeInSec = maxWaitTimeInSec,
              excludeRideHailVehicles = alreadyAllocated,
              includeRepositioningVehicles = true
            ) match {
            case Some(agentETA) =>
              alreadyAllocated = alreadyAllocated + agentETA.agentLocation.vehicleId
              allocResponses = allocResponses :+ RoutingRequiredToAllocateVehicle(
                request1Utm.addSubRequest(request2Utm),
                createRoutingRequestsForPooledTrip(List(request1Utm, request2Utm), agentETA.agentLocation, tick)
              )
              // When we group request 2 with 1 we need to remove it from the buffer
              // so it won't be processed again (it's fate is now tied to request 1)
              removeRequestFromBuffer(request2Utm)
            case None =>
              allocResponses = allocResponses :+ NoVehicleAllocated(request1Utm)
              allocResponses = allocResponses :+ NoVehicleAllocated(request2Utm)
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
      SpaceTime((rideHailLocation.getCurrentLocationUTM(tick, rideHailManager.beamServices), startTime)),
      CAR,
      asDriver = false,
      needsToCalculateCost = true
    )

    // Pickups first
    requests.foreach { req =>
      val routeReq2Pickup = RoutingRequest(
        rideHailVehicleAtOrigin.locationUTM.loc,
        req.pickUpLocationUTM,
        startTime,
        withTransit = false,
        Some(req.customer.personId),
        Vector(rideHailVehicleAtOrigin),
        triggerId = req.triggerId
      )
      routeReqs = routeReqs :+ routeReq2Pickup
      tempPickDropStore.put(routeReq2Pickup.requestId, MobilityRequest.simpleRequest(Pickup, Some(req.customer), None))

      rideHailVehicleAtOrigin = StreetVehicle(
        rideHailLocation.vehicleId,
        rideHailLocation.vehicleType.id,
        SpaceTime((req.pickUpLocationUTM, startTime)),
        CAR,
        asDriver = false,
        needsToCalculateCost = true
      )
    }

    // Dropoffs next
    requests.foreach { req =>
      val routeReq2Dropoff = RoutingRequest(
        rideHailVehicleAtOrigin.locationUTM.loc,
        req.destinationUTM,
        startTime,
        withTransit = false,
        Some(req.customer.personId),
        Vector(rideHailVehicleAtOrigin),
        triggerId = req.triggerId
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
        asDriver = false,
        needsToCalculateCost = true
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
    beamServices: BeamServices,
    maxWaitTimeInSec: Int
  ): VehicleAllocation = {
    val requestUpdated = RideHailRequest.projectCoordinatesToUtm(request, beamServices)
    rideHailManager.rideHailManagerHelper
      .getClosestIdleVehiclesWithinRadiusByETA(
        requestUpdated.pickUpLocationUTM,
        requestUpdated.destinationUTM,
        rideHailManager.radiusInMeters,
        pickUpTime,
        maxWaitTimeInSec,
        excludeRideHailVehicles = alreadyAllocated,
        includeRepositioningVehicles = true
      ) match {
      case Some(agentETA) =>
        RoutingRequiredToAllocateVehicle(
          requestUpdated,
          rideHailManager.createRoutingRequestsToCustomerAndDestination(
            pickUpTime,
            requestUpdated,
            agentETA.agentLocation,
            request.triggerId,
          )
        )
      case None =>
        NoVehicleAllocated(requestUpdated)
    }
  }

}
