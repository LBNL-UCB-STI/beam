//package beam.agentsim.agents.ridehail.allocation
//
//import beam.agentsim.agents.ridehail.RideHailManager.RideHailAgentLocation
//import beam.agentsim.agents.ridehail.{RideHailManager, RideHailRequest}
//import beam.router.BeamRouter.RoutingResponse
//import beam.router.Modes.BeamMode.RIDE_HAIL
//import org.matsim.api.core.v01.Id
//import org.matsim.core.utils.geometry.CoordUtils
//import org.matsim.vehicles.Vehicle
//
//import scala.collection.mutable
//
//class EVFleetAllocationManager(val rideHailManager: RideHailManager)
//    extends RideHailResourceAllocationManager(rideHailManager) {
//
//  val dummyDriverId: Id[Vehicle] = Id.create("NA", classOf[Vehicle])
//  val routeReqToDriverMap: mutable.Map[Int, Id[Vehicle]] = mutable.Map()
//
//  val requestToExcludedDrivers: mutable.Map[Int, Set[Id[Vehicle]]] = mutable.Map()
//  val repositioningLowWaitingTimes = new RepositioningLowWaitingTimes(rideHailManager)
//
//  override def allocateVehicleToCustomer(
//    vehicleAllocationRequest: AllocationRequests
//  ): VehicleAllocationResponse = {
//    val reqId = vehicleAllocationRequest.requests.head.requestId
//
//    // Update local storage of computed routes by driver
//    val routeResponsesByDriver = vehicleAllocationRequest.routingResponses
//      .groupBy { response =>
//        routeReqToDriverMap.getOrElse(response.requestId.get, dummyDriverId)
//      }
//
//    // If no route responses, then we have no agents in mind
//    var agentLocationOpt = if (routeResponsesByDriver.isEmpty) {
//      requestToExcludedDrivers.put(reqId, Set())
//      None
//    } else {
//      // Otherwise, we look through all routes returned and find the first one that
//      // corresponds to an idle driver and use this as our agentLocation
//      val maybeId = routeResponsesByDriver.keys.find(rideHailManager.getIdleVehicles.contains(_))
//      maybeId.map(rideHailManager.getIdleVehicles(_))
//    }
//    // If agentLoc None, we grab the closest Idle agents but filter outWriter any with a range that
//    // obviously cannot make the trip
//    agentLocationOpt = agentLocationOpt match {
//      case None =>
//        // go with nearest ETA
//        findNearestByETAConsideringRange(
//          vehicleAllocationRequest.requests.head,
//          requestToExcludedDrivers.getOrElse(reqId, Set())
//        )
//      case _ =>
//        agentLocationOpt
//    }
//
//    agentLocationOpt match {
//      case Some(agentLocation) if routeResponsesByDriver.contains(agentLocation.vehicleId) =>
//        // If we have an agent and routes, test whether the vehicle has sufficient range and exclude
//        // from future consideration if it fails this check
//        val routingResponses = routeResponsesByDriver.get(agentLocation.vehicleId)
//
//        val routedDisanceInM = routingResponses
//          .getOrElse(List[RoutingResponse]())
//          .map(
//            _.itineraries
//              .find(_.tripClassifier == RIDE_HAIL)
//              .fold(Double.MaxValue)(_.beamLegs().map(_.travelPath.distanceInM).sum)
//          )
//          .sum
//
//        if (rideHailManager
//              .getVehicleState(agentLocation.vehicleId)
//              .remainingRangeInM < routedDisanceInM) {
//          requestToExcludedDrivers.put(
//            reqId,
//            requestToExcludedDrivers.getOrElse(reqId, Set()) + agentLocation.vehicleId
//          )
//          findNearestByETAConsideringRange(
//            vehicleAllocationRequest.requests.head,
//            requestToExcludedDrivers.getOrElse(reqId, Set())
//          ) match {
//            case Some(newAgentLoc) =>
//              val routeRequired = RoutingRequiredToAllocateVehicles(rideHailManager.createRoutingRequestsToCustomerAndDestination(
//                  vehicleAllocationRequest.requests.head,
//                  newAgentLoc
//                ))
//              routeReqToDriverMap.put(routeRequired.routesRequired.head.requestId, agentLocation.vehicleId)
//              routeReqToDriverMap.put(routeRequired.routesRequired.last.requestId, agentLocation.vehicleId)
//              routeRequired
//            case None =>
//              NoVehicleAllocated
//          }
//        } else {
//          //          logger.error("veh: {} range: {} triplen: {}",agentLocation.vehicleId,
//          //            rideHailManager.getVehicleState(agentLocation.vehicleId).remainingRangeInM,
//          //            routingResponses.get.map(_.itineraries.map(_.legs.map(_.beamLeg.travelPath.distanceInM).sum).sum)
//          //            .sum)
//          // we're ready to do the allocation
//          // Clean up internal tracking then send the allocation with the responses
//          requestToExcludedDrivers.remove(reqId)
//          vehicleAllocationRequest.routingResponses.foreach { response =>
//            if (routeReqToDriverMap.contains(response.requestId.get))
//              routeReqToDriverMap.remove(response.requestId.get)
//          }
//          VehicleAllocation(agentLocation, routingResponses, None)
//        }
//      case Some(agentLocation) =>
//        // If we have an agent and no routes, ask for the routes
//        val routeRequired = RoutingRequiredToAllocateVehicles(rideHailManager.createRoutingRequestsToCustomerAndDestination(
//          vehicleAllocationRequest.requests.head,
//          agentLocation
//        ))
//        routeReqToDriverMap.put(routeRequired.routesRequired.head.requestId, agentLocation.vehicleId)
//        routeReqToDriverMap.put(routeRequired.routesRequired.last.requestId, agentLocation.vehicleId)
//        routeRequired
//      case None =>
//        NoVehicleAllocated
//    }
//  }
//
//  // Fine the closest Idle agents but filter outWriter any with a range that
//  // obviously cannot make the trip (range is less than 1.36xEuclidean distance). this 1.36 factor is
//  // informed by https://www.ncbi.nlm.nih.gov/pmc/articles/PMC3835347/pdf/nihms436252.pdf and basically
//  // means that we allow for some routes to surprise us and be less than the average ratio of routed to
//  // Euclidean distance (1.41), but we can't be more permissive because otherwise we can get into cases
//  // where we spend a lot of time routing for agents that end up not making the cut
//  def findNearestByETAConsideringRange(
//    request: RideHailRequest,
//    excludeTheseDrivers: Set[Id[Vehicle]] = Set()
//  ): Option[RideHailAgentLocation] = {
//    val set1 = rideHailManager
//      .getClosestIdleVehiclesWithinRadiusByETA(
//        request.pickUpLocation,
//        rideHailManager.radiusInMeters,
//        request.departAt.atTime
//      )
//    val set2 = set1.filterNot(rhETa => excludeTheseDrivers.contains(rhETa.agentLocation.vehicleId))
////    logger.info("num excluded: {}",excludeTheseDrivers.size)
//    val custOriginToDestEuclidDistance = CoordUtils
//      .calcProjectedEuclideanDistance(request.pickUpLocation, request.destination)
//    val set3 = set2.filter { rhEta =>
//      //      logger.error(rhEta.agentLocation.vehicleId.toString)
//      //      logger.error(rideHailManager
//      //        .getVehicleState(rhEta.agentLocation.vehicleId)
//      //        .remainingRangeInM.toString)
//      //      logger.error(rhEta.distance.toString)
//      rideHailManager
//        .getVehicleState(rhEta.agentLocation.vehicleId)
//        .remainingRangeInM >= (rhEta.distance + custOriginToDestEuclidDistance) * 1.36
//    }
//    //    logger.error(set1.toString())
//    //    logger.error(set2.toString())
//    //    logger.error(set3.toString())
//    set3.headOption
//      .map(_.agentLocation)
//  }
//
//
//  /*
//   * Finally, we delgate repositioning to the repositioning manager. Only need this if running in multi-iteration mode.
//   */
////  override def repositionVehicles(tick: Double): Vector[(Id[Vehicle], Location)] = {
////    repositioningLowWaitingTimes.repositionVehicles(tick)
////  }
//}
