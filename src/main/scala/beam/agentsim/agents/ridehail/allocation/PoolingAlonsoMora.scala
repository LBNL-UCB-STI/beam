package beam.agentsim.agents.ridehail.allocation

import beam.agentsim.agents._
import beam.agentsim.agents.ridehail.AlonsoMoraPoolingAlgForRideHail._
import beam.agentsim.agents.ridehail.RideHailManager.PoolingInfo
import beam.agentsim.agents.ridehail._
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.{Location, RoutingRequest}
import beam.router.BeamSkimmer
import beam.router.Modes.BeamMode.CAR
import org.matsim.api.core.v01.Id
import org.matsim.core.utils.collections.QuadTree
import org.matsim.vehicles.Vehicle

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.{Await, TimeoutException}

class PoolingAlonsoMora(val rideHailManager: RideHailManager)
    extends RideHailResourceAllocationManager(rideHailManager) {

  val randomRepositioning: RandomRepositioning = new RandomRepositioning(rideHailManager)
  val tempScheduleStore: mutable.Map[Int, List[MobilityRequest]] = mutable.Map()

  val spatialPoolCustomerReqs: QuadTree[CustomerRequest] = new QuadTree[CustomerRequest](
    rideHailManager.activityQuadTreeBounds.minx,
    rideHailManager.activityQuadTreeBounds.miny,
    rideHailManager.activityQuadTreeBounds.maxx,
    rideHailManager.activityQuadTreeBounds.maxy
  )

  val defaultBeamVehilceTypeId = Id.create(
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
        val timeCostFactors = rideHailManager.beamSkimmer.getRideHailPoolingTimeAndCostRatios(
          inquiry.pickUpLocationUTM,
          inquiry.destinationUTM,
          inquiry.departAt,
          defaultBeamVehilceTypeId
        )
        SingleOccupantQuoteAndPoolingInfo(
          agentETA.agentLocation,
          Some(PoolingInfo(timeCostFactors._1, timeCostFactors._2))
        )
      case None =>
        NoVehiclesAvailable
    }
  }

  override def allocateVehiclesToCustomers(
    tick: Int,
    vehicleAllocationRequest: AllocationRequests
  ): AllocationResponse = {
    rideHailManager.log.debug("Alloc requests {}", vehicleAllocationRequest.requests.size)
    var toAllocate: Set[RideHailRequest] = Set()
    var toFinalize: Set[RideHailRequest] = Set()
    var allocResponses: List[VehicleAllocation] = List()
    var alreadyAllocated: Set[Id[Vehicle]] = Set()
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
      if (routeResponses.find(_.itineraries.isEmpty).isDefined) {
        allocResponses = allocResponses :+ NoVehicleAllocated(request)
        if (tempScheduleStore.contains(request.requestId)) tempScheduleStore.remove(request.requestId)
      } else {
        // Make sure vehicle still available
        val vehicleId = routeResponses.head.itineraries.head.legs.head.beamVehicleId
        if (rideHailManager.vehicleManager.getIdleAndInServiceVehicles.contains(vehicleId) && !alreadyAllocated
              .contains(vehicleId)) {
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
            rideHailManager.vehicleManager.getIdleAndInServiceVehicles(vehicleId),
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
      implicit val skimmer: BeamSkimmer = rideHailManager.beamSkimmer
      val pooledAllocationReqs = toAllocate.filter(_.asPooled)
      val customerIdToReqs = toAllocate.map(rhr => rhr.customer.personId -> rhr).toMap
      val (availVehicles, poolCustomerReqs, offset) =
        (
          rideHailManager.vehicleManager.getIdleAndInServiceVehicles.values
            .map(
              veh =>
                createVehicleAndScheduleFromRideHailAgentLocation(
                  veh,
                  tick + rideHailManager.beamServices.beamConfig.beam.agentsim.schedulerParallelismWindow,
                  rideHailManager.beamServices
                )
            )
            .toList,
          pooledAllocationReqs.map(
            rhr =>
              createPersonRequest(
                rhr.customer,
                rhr.pickUpLocationUTM,
                tick + rideHailManager.beamServices.beamConfig.beam.agentsim.schedulerParallelismWindow,
                rhr.destinationUTM
              )
          ),
          rideHailManager.beamServices.beamConfig.beam.agentsim.schedulerParallelismWindow
        )
//        if (rideHailManager.vehicleManager.inServiceRideHailVehicles.isEmpty) {
//          (
//            rideHailManager.vehicleManager.getIdleAndInServiceVehicles.values
//              .map(
//                veh =>
//                  createVehicleAndScheduleFromRideHailAgentLocation(
//                    veh,
//                    tick,
//                    rideHailManager.beamServices
//                )
//              )
//              .toList,
//            pooledAllocationReqs.map(
//              rhr => createPersonRequest(rhr.customer, rhr.pickUpLocationUTM, tick, rhr.destinationUTM)
//            ),
//            0
//          )
//        } else {
//          (
//            rideHailManager.vehicleManager.inServiceRideHailVehicles.values
//              .map(
//                veh =>
//                  createVehicleAndScheduleFromRideHailAgentLocation(
//                    veh,
//                    tick + rideHailManager.beamServices.beamConfig.beam.agentsim.schedulerParallelismWindow,
//                    rideHailManager.beamServices
//                )
//              )
//              .toList,
//            pooledAllocationReqs.map(
//              rhr =>
//                createPersonRequest(
//                  rhr.customer,
//                  rhr.pickUpLocationUTM,
//                  tick + rideHailManager.beamServices.beamConfig.beam.agentsim.schedulerParallelismWindow,
//                  rhr.destinationUTM
//              )
//            ),
//            rideHailManager.beamServices.beamConfig.beam.agentsim.schedulerParallelismWindow
//          )
//        }

      spatialPoolCustomerReqs.clear()
      poolCustomerReqs.foreach { d =>
        spatialPoolCustomerReqs.put(d.pickup.activity.getCoord.getX, d.pickup.activity.getCoord.getY, d)
      }
      val maxRequests =
        rideHailManager.beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.alonsoMora.maxRequestsPerVehicle
      val pickupWindow =
        rideHailManager.beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.alonsoMora.pickupTimeWindowInSec
      val dropoffWindow =
        rideHailManager.beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.alonsoMora.dropoffTimeWindowInSec

//      rideHailManager.log.debug("%%%%% Num avail: {}", availVehicles.size)
//      rideHailManager.log.debug("%%%%% Requests: {}", spatialPoolCustomerReqs.values().asScala.map(_.toString).mkString("\n"))
//      rideHailManager.log.debug("%%%%% Available Vehicles: {}", availVehicles.map(_.vehicle.id).mkString(","))
      val algo = new AsyncAlonsoMoraAlgForRideHail(
        spatialPoolCustomerReqs,
        availVehicles,
        Map[MobilityRequestType, Double](
          (Pickup, pickupWindow + offset),
          (Dropoff, dropoffWindow),
          (EnRoute, Int.MaxValue - 30000000),
          (Relocation, Int.MaxValue - 30000000)
        ),
        maxRequestsPerVehicle = maxRequests,
        rideHailManager.beamServices
      )
      import scala.concurrent.duration._
      val assignment = try {
        Await.result(algo.greedyAssignment(tick), atMost = 2.minutes)
      } catch {
        case e: TimeoutException =>
          rideHailManager.log.error("timeout of AsyncAlonsoMoraAlgForRideHail no allocations made")
          List()
      }
      if (!rideHailManager.vehicleManager.inServiceRideHailVehicles.isEmpty) {
        val i = 0
      }

      assignment.foreach {
        case (theTrip, vehicleAndOldSchedule, cost) =>
          // Pooling alg can return a schedule identical to one that is already in progress, for these we ignore
          if (theTrip.schedule != vehicleAndOldSchedule.schedule) {
              rideHailManager.log.debug(
                "%%%%% Assigned vehicle {} the trip: \n {}",
                vehicleAndOldSchedule.vehicle.id,
                theTrip
              )
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
                    orig.activity.getCoord,
                    dest.activity.getCoord,
                    origin.time,
                    withTransit = false,
                    IndexedSeq(
                      StreetVehicle(
                        Id.create(vehicleAndOldSchedule.vehicle.id.toString, classOf[Vehicle]),
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
      pooledAllocationReqs.filterNot(req => wereAllocated.contains(req.requestId)).foreach { unsatisfiedReq =>
        allocResponses = allocResponses :+ NoVehicleAllocated(unsatisfiedReq)
      }
      // Now satisfy the solo customers
      toAllocate.filterNot(_.asPooled).foreach { req =>
        Pooling.serveOneRequest(req, tick, alreadyAllocated, rideHailManager) match {
          case res @ RoutingRequiredToAllocateVehicle(_, routes) =>
            allocResponses = allocResponses :+ res
            alreadyAllocated = alreadyAllocated + routes.head.streetVehicles.head.id
          case res =>
            allocResponses = allocResponses :+ res
        }
      }
    }
    rideHailManager.log.debug(
      "AllocResponses: {}",
      allocResponses.groupBy(_.getClass).map(x => s"${x._1.getSimpleName} -- ${x._2.size}").mkString("\t")
    )
    VehicleAllocations(allocResponses)
  }
  override def repositionVehicles(tick: Double): Vector[(Id[Vehicle], Location)] = {
    randomRepositioning.repositionVehicles(tick)
  }

}
