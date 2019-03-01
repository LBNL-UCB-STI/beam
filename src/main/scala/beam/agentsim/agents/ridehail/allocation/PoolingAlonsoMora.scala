package beam.agentsim.agents.ridehail.allocation

import beam.agentsim.agents.ridehail.AlonsoMoraPoolingAlgForRideHail._
import beam.agentsim.agents.ridehail.RideHailManager.PoolingInfo
import beam.agentsim.agents.ridehail._
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.RoutingRequest
import beam.router.BeamSkimmer
import beam.router.Modes.BeamMode.CAR
import org.matsim.api.core.v01.Id
import org.matsim.core.utils.collections.QuadTree
import org.matsim.vehicles.Vehicle

import scala.collection.mutable
import scala.concurrent.{Await, TimeoutException}

class PoolingAlonsoMora(val rideHailManager: RideHailManager)
    extends RideHailResourceAllocationManager(rideHailManager) {

  val tempScheduleStore: mutable.Map[Int, List[MobilityServiceRequest]] = mutable.Map()

  val spatialPoolCustomerReqs: QuadTree[CustomerRequest] = new QuadTree[CustomerRequest](
    rideHailManager.quadTreeBounds.minx,
    rideHailManager.quadTreeBounds.miny,
    rideHailManager.quadTreeBounds.maxx,
    rideHailManager.quadTreeBounds.maxy
  )

  override def respondToInquiry(inquiry: RideHailRequest): InquiryResponse = {
    rideHailManager.vehicleManager
      .getClosestIdleVehiclesWithinRadiusByETA(
        inquiry.pickUpLocationUTM,
        rideHailManager.radiusInMeters,
        inquiry.departAt
      )
      .headOption match {
      case Some(agentETA) =>
        SingleOccupantQuoteAndPoolingInfo(agentETA.agentLocation, Some(PoolingInfo(1.1, 0.6)))
      case None =>
        NoVehiclesAvailable
    }
  }

  override def allocateVehiclesToCustomers(
    tick: Int,
    vehicleAllocationRequest: AllocationRequests
  ): AllocationResponse = {
    logger.debug("Alloc requests {}", vehicleAllocationRequest.requests.size)
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
      val indexedResponses = routeResponses.map(resp => (resp.requestId -> resp)).toMap

      // First check for broken route responses (failed routing attempt)
      if (routeResponses.find(_.itineraries.size == 0).isDefined) {
        allocResponses = allocResponses :+ NoVehicleAllocated(request)
      } else {
        // Make sure vehicle still available
        val vehicleId = routeResponses.head.itineraries.head.legs.head.beamVehicleId
        if (rideHailManager.vehicleManager.getIdleVehicles.contains(vehicleId) && !alreadyAllocated.contains(vehicleId)) {
          alreadyAllocated = alreadyAllocated + vehicleId
          // NOTE: PickDrops use convention of storing an optional person Id and leg. The leg is intended to be the leg
          // **to** the pickup or dropoff, not from the pickup or dropoff. This is the reverse of the convention used
          // in MobilityServiceRequest
          val pickDropIdAndLegs = if (tempScheduleStore.contains(request.requestId)) {
            // Pooled response
            val mobilityServiceRequests = tempScheduleStore.remove(request.requestId).get

            mobilityServiceRequests
              .sliding(2)
              .map { reqs =>
                reqs.head.routingRequestId match {
                  case Some(routingRequestId) =>
                    PickDropIdAndLeg(
                      reqs.last.person,
                      indexedResponses(routingRequestId).itineraries.head.legs.headOption
                    )
                  case None =>
                    PickDropIdAndLeg(reqs.last.person, None)
                }
              }
              .toList
          } else {
            // Solo response
            List(
              PickDropIdAndLeg(request.customer, routeResponses.head.itineraries.head.legs.headOption),
              PickDropIdAndLeg(request.customer, routeResponses.last.itineraries.head.legs.headOption)
            )
          }
          allocResponses = allocResponses :+ VehicleMatchedToCustomers(
            request,
            rideHailManager.vehicleManager.getIdleVehicles(vehicleId),
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
    if (toPool.size > 0) {
      implicit val skimmer: BeamSkimmer = new BeamSkimmer()
      val pooledAllocationReqs = toPool.filter(_.asPooled)
      val poolCustomerReqs = pooledAllocationReqs.map(
        rhr => createPersonRequest(rhr.customer, rhr.pickUpLocationUTM, tick, rhr.destinationUTM)
      )
      val customerIdToReqs = toPool.map(rhr => rhr.customer.personId -> rhr).toMap
      val availVehicles = rideHailManager.vehicleManager.availableRideHailVehicles.values
        .map(veh => createVehicleAndSchedule(veh.vehicleId.toString, veh.currentLocationUTM.loc, tick))

      spatialPoolCustomerReqs.clear()
      poolCustomerReqs.foreach { d =>
        spatialPoolCustomerReqs.put(d.pickup.activity.getCoord.getX, d.pickup.activity.getCoord.getY, d)
      }

//      logger.info("Num custs: {} num vehs: {}", spatialPoolCustomerReqs.size(), availVehicles.size)
      val algo = new AsyncAlonsoMoraAlgForRideHail(
        spatialPoolCustomerReqs,
        availVehicles.toList,
        Map[MobilityServiceRequestType, Int]((Pickup, 6 * 60), (Dropoff, 10 * 60)),
        maxRequestsPerVehicle = 5
      )
      import scala.concurrent.duration._
      val assignment = try {
        Await.result(algo.greedyAssignment(), atMost = 2.minutes)
      } catch {
        case e: TimeoutException =>
//          logger.error("timeout of AsyncAlonsoMoraAlgForRideHail falling back to synchronous")
//          val algo = new AlonsoMoraPoolingAlgForRideHail(
//            spatialPoolCustomerReqs,
//            availVehicles.toList,
//            Map[MobilityServiceRequestType, Int]((Pickup, 6 * 60), (Dropoff, 10 * 60)),
//            maxRequestsPerVehicle = 100
//          )
//          val rvGraph: RVGraph = algo.pairwiseRVGraph
//          val rtvGraph = algo.rTVGraph(rvGraph)
//          algo.greedyAssignment(rtvGraph)
          logger.error("timeout of AsyncAlonsoMoraAlgForRideHail no allocations made")
          List()
      }

      assignment.foreach {
        case (theTrip, vehicleAndSchedule, cost) =>
          alreadyAllocated = alreadyAllocated + vehicleAndSchedule.vehicle.id
          var newRideHailRequest: Option[RideHailRequest] = None
          var scheduleToCache: List[MobilityServiceRequest] = List()
          val rReqs = theTrip.schedule
            .sliding(2)
            .flatMap { wayPoints =>
              val orig = wayPoints(0)
              val dest = wayPoints(1)
              val origin = SpaceTime(orig.activity.getCoord, orig.serviceTime.toInt)
              if (newRideHailRequest.isEmpty && orig.person != null) {
                newRideHailRequest = Some(customerIdToReqs(orig.person.personId))
              } else if (orig.person != null && !newRideHailRequest.get.customer.equals(orig.person) && newRideHailRequest.get.groupedWithOtherRequests
                           .find(_.customer.equals(orig.person))
                           .isEmpty) {
                newRideHailRequest = Some(newRideHailRequest.get.addSubRequest(customerIdToReqs(orig.person.personId)))
                removeRequestFromBuffer(customerIdToReqs(orig.person.personId))
              }
              if (rideHailManager.beamServices.geo.distUTMInMeters(orig.activity.getCoord, dest.activity.getCoord) < rideHailManager.beamServices.beamConfig.beam.agentsim.thresholdForWalkingInMeters) {
                scheduleToCache = scheduleToCache :+ orig
                None
              } else {
                val routingRequest = RoutingRequest(
                  orig.activity.getCoord,
                  dest.activity.getCoord,
                  origin.time,
                  IndexedSeq(),
                  IndexedSeq(
                    StreetVehicle(
                      Id.create(vehicleAndSchedule.vehicle.id.toString, classOf[Vehicle]),
                      vehicleAndSchedule.vehicle.beamVehicleType.id,
                      origin,
                      CAR,
                      asDriver = true
                    )
                  )
                )
                scheduleToCache = scheduleToCache :+ orig.copy(routingRequestId = Some(routingRequest.requestId))
                Some(routingRequest)
              }
            }
            .toList
          allocResponses = allocResponses :+ RoutingRequiredToAllocateVehicle(newRideHailRequest.get, rReqs)
          tempScheduleStore.put(newRideHailRequest.get.requestId, scheduleToCache :+ theTrip.schedule.last)
      }
      // Anyone unsatisfied must be assigned NoVehicleAllocated
      val wereAllocated = allocResponses
        .map(resp => resp.request.groupedWithOtherRequests.map(_.requestId).toSet + resp.request.requestId)
        .flatten
        .toSet
      pooledAllocationReqs.filterNot(req => wereAllocated.contains(req.requestId)).foreach { unsatisfiedReq =>
        allocResponses = allocResponses :+ NoVehicleAllocated(unsatisfiedReq)
      }
      // Now satisfy the solo customers
      toPool.filterNot(_.asPooled).foreach { req =>
        Pooling.serveOneRequest(req, tick, alreadyAllocated, rideHailManager) match {
          case res @ RoutingRequiredToAllocateVehicle(_, routes) =>
            allocResponses = allocResponses :+ res
            alreadyAllocated = alreadyAllocated + routes.head.streetVehicles.head.id
          case res =>
            allocResponses = allocResponses :+ res
        }
      }
    }
    logger.debug(
      "AllocResponses: {}",
      allocResponses.groupBy(_.getClass).map(x => s"${x._1.getSimpleName} -- ${x._2.size}").mkString("\t")
    )
    VehicleAllocations(allocResponses)
  }

}
