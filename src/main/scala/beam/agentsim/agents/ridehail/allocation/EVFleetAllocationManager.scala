package beam.agentsim.agents.ridehail.allocation

import beam.agentsim.agents.ridehail.RideHailManager.RideHailAgentLocation
import beam.agentsim.agents.ridehail.{RideHailManager, RideHailRequest}
import beam.router.BeamRouter.Location
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

class EVFleetAllocationManager(val rideHailManager: RideHailManager)
    extends RideHailResourceAllocationManager(rideHailManager) {

  val dummyDriverId = Id.create("NA", classOf[Vehicle])
  val routeReqToDriverMap = scala.collection.mutable.Map[Int, Id[Vehicle]]()
  val requestToExcludedDrivers = scala.collection.mutable.Map[Int, Set[Id[Vehicle]]]()
  val repositioningLowWaitingTimes = new RepositioningLowWaitingTimes(rideHailManager)

  override def proposeVehicleAllocation(
    vehicleAllocationRequest: VehicleAllocationRequest
  ): VehicleAllocationResponse = {
    val reqId = vehicleAllocationRequest.request.requestId

    // Update local storage of computed routes by driver
    val routeResponsesByDriver = vehicleAllocationRequest.routingResponses
      .groupBy { response =>
        routeReqToDriverMap.getOrElse(response.requestId.get, dummyDriverId)
      }

    // If no route responses, then we have no agents in mind
    var agentLocationOpt = if (routeResponsesByDriver.isEmpty) {
      requestToExcludedDrivers.put(reqId, Set())
      None
    } else {
      // Otherwise, we look through all routes returned and find the first one that
      // corresponds to an idle driver and use this as our agentLocation
      val maybeId = routeResponsesByDriver.keys.find(rideHailManager.getIdleVehicles.contains(_))
      maybeId.map(rideHailManager.getIdleVehicles(_))
    }
    // If agentLoc None, we grab the closest Idle agents but filter out any with a range that
    // obviously cannot make the trip (range is less than 1.26xEuclidean distance. this factor is
    // based on https://www.ncbi.nlm.nih.gov/pmc/articles/PMC3835347/pdf/nihms436252.pdf and basically
    // means that less than 10% of the time would a vehicle with this range actually be able to cover
    // the true trip due to the variability between Euclidean and actual distance
    agentLocationOpt = agentLocationOpt match {
      case None =>
        // go with nearest ETA
        findNearestByETAConsideringRange(
          vehicleAllocationRequest.request,
          requestToExcludedDrivers.getOrElse(reqId, Set())
        )
      case _ =>
        agentLocationOpt
    }

    agentLocationOpt match {
      case Some(agentLocation) if routeResponsesByDriver.contains(agentLocation.vehicleId) =>
        // If we have an agent and routes, test whether the vehicle has sufficient range and exclude
        // from future consideration if it fails this check
        val routingResponses = routeResponsesByDriver.get(agentLocation.vehicleId)

        if (rideHailManager
              .getVehicleState(agentLocation.vehicleId)
              .remainingRangeInM < routingResponses.get
              .map(_.itineraries.map(_.legs.map(_.beamLeg.travelPath.distanceInM).sum).sum)
              .sum) {
          requestToExcludedDrivers.put(
            reqId,
            requestToExcludedDrivers.getOrElse(reqId, Set()) + agentLocation.vehicleId
          )
          findNearestByETAConsideringRange(
            vehicleAllocationRequest.request,
            requestToExcludedDrivers.getOrElse(reqId, Set())
          ) match {
            case Some(newAgentLoc) =>
              makeRouteRequest(vehicleAllocationRequest.request, newAgentLoc)
            case None =>
              NoVehicleAllocated
          }
        } else {
//          logger.error("veh: {} range: {} triplen: {}",agentLocation.vehicleId,
//            rideHailManager.getVehicleState(agentLocation.vehicleId).remainingRangeInM,
//            routingResponses.get.map(_.itineraries.map(_.legs.map(_.beamLeg.travelPath.distanceInM).sum).sum)
//            .sum)
          // we're ready to do the allocation
          // Clean up internal tracking then send the allocation with the responses
          requestToExcludedDrivers.remove(reqId)
          vehicleAllocationRequest.routingResponses.foreach { response =>
            if (routeReqToDriverMap.contains(response.requestId.get))
              routeReqToDriverMap.remove(response.requestId.get)
          }
          VehicleAllocation(agentLocation, routingResponses)
        }
      case Some(agentLocation) =>
        // If we have an agent and no routes, ask for the routes
        makeRouteRequest(vehicleAllocationRequest.request, agentLocation)
      case None =>
        NoVehicleAllocated
    }
  }

  def findNearestByETAConsideringRange(
    request: RideHailRequest,
    excludeTheseDrivers: Set[Id[Vehicle]] = Set()
  ) = {
    val set1 = rideHailManager
      .getClosestIdleVehiclesWithinRadiusByETA(
        request.pickUpLocation,
        rideHailManager.radiusInMeters,
        request.departAt.atTime
      )
    val set2 = set1.filterNot(rhETa => excludeTheseDrivers.contains(rhETa.agentLocation.vehicleId))
    val set3 = set2.filter { rhEta =>
//      logger.error(rhEta.agentLocation.vehicleId.toString)
//      logger.error(rideHailManager
//        .getVehicleState(rhEta.agentLocation.vehicleId)
//        .remainingRangeInM.toString)
//      logger.error(rhEta.distance.toString)
      rideHailManager
        .getVehicleState(rhEta.agentLocation.vehicleId)
        .remainingRangeInM >= rhEta.distance * 1.26
    }
//    logger.error(set1.toString())
//    logger.error(set2.toString())
//    logger.error(set3.toString())
    set3.headOption
      .map(_.agentLocation)
  }

  def makeRouteRequest(request: RideHailRequest, agentLocation: RideHailAgentLocation) = {
    val routeRequests = rideHailManager.createRoutingRequestsToCustomerAndDestination(
      request,
      agentLocation
    )
    routeReqToDriverMap.put(routeRequests.head.requestId, agentLocation.vehicleId)
    routeReqToDriverMap.put(routeRequests.last.requestId, agentLocation.vehicleId)

    RoutingRequiredToAllocateVehicle(
      request,
      routeRequests
    )
  }

  /*
   * Finally, we delgate repositioning to the repositioning manager
   */
  override def repositionVehicles(tick: Double): Vector[(Id[Vehicle], Location)] = {
    repositioningLowWaitingTimes.repositionVehicles(tick)
  }
}
