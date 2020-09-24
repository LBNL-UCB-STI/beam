package beam.agentsim.agents.ridehail.allocation

import beam.agentsim.agents._
import beam.agentsim.agents.ridehail.RideHailManager.PoolingInfo
import beam.agentsim.agents.ridehail.RideHailMatching.CustomerRequest
import beam.agentsim.agents.ridehail._
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.RoutingRequest
import beam.router.Modes.BeamMode.CAR
import beam.router.skim.Skims
import beam.sim.BeamServices
import org.matsim.api.core.v01.Id
import org.matsim.core.utils.collections.QuadTree

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.{Await, TimeoutException}

class PoolingAlonsoMora(val rideHailManager: RideHailManager)
    extends RideHailResourceAllocationManager(rideHailManager) {

  val tempScheduleStore: mutable.Map[Int, List[MobilityRequest]] = mutable.Map()

  val spatialPoolCustomerReqs: QuadTree[CustomerRequest] = new QuadTree[CustomerRequest](
    rideHailManager.activityQuadTreeBounds.minx,
    rideHailManager.activityQuadTreeBounds.miny,
    rideHailManager.activityQuadTreeBounds.maxx,
    rideHailManager.activityQuadTreeBounds.maxy
  )

  val defaultBeamVehilceTypeId: Id[BeamVehicleType] = Id.create(
    rideHailManager.beamServices.beamConfig.beam.agentsim.agents.rideHail.initialization.procedural.vehicleTypeId,
    classOf[BeamVehicleType]
  )

  override def respondToInquiry(inquiry: RideHailRequest): InquiryResponse = {
    rideHailManager.vehicleManager
      .getClosestIdleVehiclesWithinRadiusByETA(
        inquiry.pickUpLocationUTM,
        inquiry.destinationUTM,
        rideHailManager.radiusInMeters,
        inquiry.departAt
      ) match {
      case Some(agentETA) =>
        val timeCostFactors = rideHailManager.beamServices.skims.od_skimmer.getRideHailPoolingTimeAndCostRatios(
          inquiry.pickUpLocationUTM,
          inquiry.destinationUTM,
          inquiry.departAt,
          defaultBeamVehilceTypeId,
          rideHailManager.beamServices
        )
        SingleOccupantQuoteAndPoolingInfo(
          agentETA.agentLocation,
          Some(PoolingInfo(timeCostFactors._1, timeCostFactors._2))
        )
      case None =>
        NoVehiclesAvailable
    }
  }

  def createMatchingAlgorithm(availVehicles: List[RideHailMatching.VehicleAndSchedule]): RideHailMatching = {
    rideHailManager.beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.matchingAlgorithm match {
      case "ASYNC_GREEDY_VEHICLE_CENTRIC_MATCHING" =>
        new AsyncGreedyVehicleCentricMatching(
          spatialPoolCustomerReqs,
          availVehicles,
          rideHailManager.beamServices
        )
      case "ALONSO_MORA_MATCHING_WITH_ASYNC_GREEDY_ASSIGNMENT" =>
        new AlonsoMoraMatchingWithAsyncGreedyAssignment(
          spatialPoolCustomerReqs,
          availVehicles,
          rideHailManager.beamServices
        )
      case "ALONSO_MORA_MATCHING_WITH_MIP_ASSIGNMENT" =>
        new AlonsoMoraMatchingWithMIPAssignment(
          spatialPoolCustomerReqs,
          availVehicles,
          rideHailManager.beamServices
        )
      case algorithmName =>
        throw new RuntimeException(s"Unknown matching algorithm $algorithmName for alonsoMora")
    }
  }

  override def allocateVehiclesToCustomers(
    tick: Int,
    vehicleAllocationRequest: AllocationRequests,
    beamServices: BeamServices
  ): AllocationResponse = {
    rideHailManager.log.debug("Alloc requests {}", vehicleAllocationRequest.requests.size)
    var toAllocate: Set[RideHailRequest] = Set()
    var toFinalize: Set[RideHailRequest] = Set()
    var allocResponses: Vector[VehicleAllocation] = Vector()
    var alreadyAllocated: Set[Id[BeamVehicle]] = Set()
    rideHailManager.doNotUseInAllocation.foreach { veh =>
      alreadyAllocated = alreadyAllocated + veh.asInstanceOf[Id[BeamVehicle]]
    }
    vehicleAllocationRequest.requests.foreach {
      case (request, routingResponses) if routingResponses.isEmpty =>
        toAllocate += request
      case (request, _) =>
        toFinalize += request
    }
    toFinalize.foreach { request =>
      val routeResponses = vehicleAllocationRequest.requests(request)
      val indexedResponses = routeResponses.map(resp => (resp.requestId -> resp)).toMap

      // First check for broken route responses (failed routing attempt)
      if (routeResponses.exists(_.itineraries.isEmpty)) {
        allocResponses = allocResponses :+ NoVehicleAllocated(request)
        if (tempScheduleStore.contains(request.requestId)) tempScheduleStore.remove(request.requestId)
      } else {
        // Make sure vehicle still available
        val vehicleId = routeResponses.head.itineraries.head.legs.head.beamVehicleId
        val rideHailAgentLocation =
          rideHailManager.vehicleManager.getRideHailAgentLocationInIdleAndInServiceVehicles(vehicleId)
        if (rideHailAgentLocation.nonEmpty && !alreadyAllocated.contains(vehicleId)) {
          alreadyAllocated = alreadyAllocated + vehicleId
          val requestsWithLegs = if (tempScheduleStore.contains(request.requestId)) {
            // Pooled response
            tempScheduleStore.remove(request.requestId).get.map { req =>
              req.routingRequestId match {
                case Some(routingRequestId) =>
                  req.copy(beamLegAfterTag = indexedResponses(routingRequestId).itineraries.head.legs.headOption)
                case None =>
                  req
              }
            }
          } else {
            // Solo response
            List(
              MobilityRequest.simpleRequest(
                Relocation,
                Some(request.customer),
                routeResponses.head.itineraries.head.legs.headOption
              ),
              MobilityRequest
                .simpleRequest(Pickup, Some(request.customer), routeResponses.last.itineraries.head.legs.headOption),
              MobilityRequest.simpleRequest(Dropoff, Some(request.customer), None)
            )
          }
          allocResponses = allocResponses :+ VehicleMatchedToCustomers(
            request,
            rideHailAgentLocation.get,
            requestsWithLegs
          )
        } else {
          if (tempScheduleStore.contains(request.requestId)) tempScheduleStore.remove(request.requestId)
          allocResponses = allocResponses :+ NoVehicleAllocated(request)
          request.groupedWithOtherRequests.foreach { req =>
            allocResponses = allocResponses :+ NoVehicleAllocated(req)
          }
        }
      }
    }
    if (toAllocate.nonEmpty) {
      val pooledAllocationReqs = toAllocate.filter(_.asPooled)
      val customerIdToReqs = toAllocate.map(rhr => rhr.customer.personId -> rhr).toMap
      val vehiclePoolToUse =
        rideHailManager.beamScenario.beamConfig.beam.agentsim.agents.rideHail.allocationManager.requestBufferTimeoutInSeconds match {
          case 0 =>
            rideHailManager.vehicleManager.getIdleVehiclesAndFilterOutExluded.values
          case _ =>
            rideHailManager.vehicleManager.getIdleAndInServiceVehicles.values
        }
      val (availVehicles, poolCustomerReqs, offset) =
        (
          vehiclePoolToUse.map { veh =>
            val vehState = rideHailManager.vehicleManager.getVehicleState(veh.vehicleId)
            val vehAndSched = RideHailMatching.createVehicleAndScheduleFromRideHailAgentLocation(
              veh,
              Math.max(tick, veh.latestTickExperienced),
              rideHailManager.beamServices,
              vehState.totalRemainingRange - rideHailManager.beamScenario.beamConfig.beam.agentsim.agents.rideHail.rangeBufferForDispatchInMeters
            )
            if (rideHailManager.log.isDebugEnabled) {
              rideHailManager.log.debug(
                "%%%%% Vehicle {} is available with this schedule: \n {}",
                vehAndSched.vehicle.id,
                vehAndSched.schedule.map(_.toString).mkString("\n")
              )
            }
            vehAndSched
          }.toList,
          pooledAllocationReqs.map(
            rhr =>
              RideHailMatching.createPersonRequest(
                rhr.customer,
                rhr.pickUpLocationUTM,
                tick,
                rhr.destinationUTM,
                rideHailManager.beamServices
            )
          ),
          rideHailManager.beamServices.beamConfig.beam.agentsim.schedulerParallelismWindow
        )

      spatialPoolCustomerReqs.clear()
      poolCustomerReqs.foreach { d =>
        spatialPoolCustomerReqs.put(d.pickup.activity.getCoord.getX, d.pickup.activity.getCoord.getY, d)
      }
      if (rideHailManager.log.isDebugEnabled) {
        rideHailManager.log
          .debug("%%%%% Requests: {}", spatialPoolCustomerReqs.values().asScala.map(_.toString).mkString("\n"))
      }

      import scala.concurrent.duration._
      val assignment = try {
        Await.result(createMatchingAlgorithm(availVehicles).matchAndAssign(tick), atMost = 2.minutes)
      } catch {
        case e: TimeoutException =>
          rideHailManager.log.error("timeout of Matching Algorithm with no allocations made")
          List()
      }

      assignment.foreach { theTrip =>
        val vehicleAndOldSchedule = theTrip.vehicle.get
        // Pooling alg can return a schedule identical to one that is already in progress, for these we ignore
        if (theTrip.schedule != vehicleAndOldSchedule.schedule) {
          rideHailManager.log.debug(
            "%%%%% Assigned vehicle {} the trip @ {}: \n {}",
            vehicleAndOldSchedule.vehicle.id,
            tick,
            theTrip
          )
          if (rideHailManager.vehicleManager
                .getRideHailAgentLocation(vehicleAndOldSchedule.vehicle.id)
                .latestTickExperienced > 0) {
            rideHailManager.log.debug(
              "\tlatest tick by vehicle {} is {}",
              vehicleAndOldSchedule.vehicle.id,
              rideHailManager.vehicleManager
                .getRideHailAgentLocation(vehicleAndOldSchedule.vehicle.id)
                .latestTickExperienced
            )
          }
          alreadyAllocated = alreadyAllocated + vehicleAndOldSchedule.vehicle.id
          var newRideHailRequest: Option[RideHailRequest] = None
          var scheduleToCache: List[MobilityRequest] = List()
          val rReqs = (theTrip.schedule
            .find(_.tag == EnRoute)
            .toList ++ theTrip.schedule.reverse.takeWhile(_.tag != EnRoute).reverse)
            .sliding(2)
            .flatMap { wayPoints =>
              val orig = wayPoints(0)
              val dest = wayPoints(1)
              val origin = SpaceTime(orig.activity.getCoord, orig.serviceTime)
              if (newRideHailRequest.isEmpty && orig.person.isDefined && customerIdToReqs.contains(
                    orig.person.get.personId
                  )) {
                newRideHailRequest = Some(customerIdToReqs(orig.person.get.personId))
              } else if (orig.person.isDefined &&
                         newRideHailRequest.isDefined &&
                         !newRideHailRequest.get.customer.equals(orig.person.get) &&
                         !newRideHailRequest.get.groupedWithOtherRequests.exists(_.customer.equals(orig.person.get)) &&
                         customerIdToReqs.contains(orig.person.get.personId)) {
                newRideHailRequest =
                  Some(newRideHailRequest.get.addSubRequest(customerIdToReqs(orig.person.get.personId)))
                removeRequestFromBuffer(customerIdToReqs(orig.person.get.personId))
              }
              if (rideHailManager.beamServices.geo.distUTMInMeters(orig.activity.getCoord, dest.activity.getCoord) < rideHailManager.beamServices.beamConfig.beam.agentsim.thresholdForWalkingInMeters) {
                scheduleToCache = scheduleToCache :+ orig
                None
              } else {
                val routingRequest = RoutingRequest(
                  originUTM = orig.activity.getCoord,
                  destinationUTM = dest.activity.getCoord,
                  departureTime = origin.time,
                  withTransit = false,
                  personId = orig.person.map(_.personId),
                  streetVehicles = IndexedSeq(
                    StreetVehicle(
                      vehicleAndOldSchedule.vehicle.id,
                      vehicleAndOldSchedule.vehicle.beamVehicleType.id,
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
      }
      // Anyone unsatisfied must be assigned NoVehicleAllocated
      val wereAllocated = allocResponses
        .flatMap(resp => resp.request.groupedWithOtherRequests.map(_.requestId).toSet + resp.request.requestId)
        .toSet

      val nonAllocated = pooledAllocationReqs.filterNot(req => wereAllocated.contains(req.requestId))
      var s = System.currentTimeMillis()
      nonAllocated.foreach { unsatisfiedReq =>
        Pooling.serveOneRequest(unsatisfiedReq, tick, alreadyAllocated, rideHailManager, beamServices) match {
          case res @ RoutingRequiredToAllocateVehicle(_, routes) =>
            allocResponses = allocResponses :+ res
            alreadyAllocated = alreadyAllocated + routes.head.streetVehicles.head.id
          case res =>
            allocResponses = allocResponses :+ res
        }
      }
      var e = System.currentTimeMillis()
      logger.debug(s"Served nonAllocated ${nonAllocated.size} in ${e - s} ms")

      s = System.currentTimeMillis()
      // Now satisfy the solo customers
      val soloCustomer = toAllocate.filterNot(_.asPooled)
      toAllocate.filterNot(_.asPooled).foreach { req =>
        Pooling.serveOneRequest(req, tick, alreadyAllocated, rideHailManager, beamServices) match {
          case res @ RoutingRequiredToAllocateVehicle(_, routes) =>
            allocResponses = allocResponses :+ res
            alreadyAllocated = alreadyAllocated + routes.head.streetVehicles.head.id
          case res =>
            allocResponses = allocResponses :+ res
        }
      }
      e = System.currentTimeMillis()
      logger.debug(s"Served soloCustomer ${soloCustomer.size} in ${e - s} ms")
    }
    if (rideHailManager.log.isDebugEnabled) {
      rideHailManager.log.debug(
        "AllocResponses: {}",
        allocResponses.groupBy(_.getClass).map(x => s"${x._1.getSimpleName} -- ${x._2.size}").mkString("\t")
      )
    }
    VehicleAllocations(allocResponses)
  }
}
