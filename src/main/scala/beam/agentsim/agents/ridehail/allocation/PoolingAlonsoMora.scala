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
import org.matsim.vehicles.Vehicle

import scala.collection.mutable
import scala.concurrent.Await

class PoolingAlonsoMora(val rideHailManager: RideHailManager) extends RideHailResourceAllocationManager(rideHailManager) {

  val tempScheduleStore: mutable.Map[Int,List[MobilityServiceRequest]] = mutable.Map()

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
    logger.debug("buffer size: {}",vehicleAllocationRequest.requests.size)
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
          val mobilityServiceRequests = tempScheduleStore.remove(request.requestId).get

          val pickDropIdAndLegs = mobilityServiceRequests.map{ req =>
            req.routingRequestId match {
              case Some(routingRequestId) =>
                PickDropIdAndLeg(req.person.get,indexedResponses(routingRequestId).itineraries.head.legs.headOption)
              case None =>
                PickDropIdAndLeg(req.person.get,None)
            }
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
    if(toPool.isEmpty)logger.info("Allocated")
    if(toPool.size > 0){
      implicit val skimmer: BeamSkimmer = new BeamSkimmer()
      val customerReqs = toPool.map(rhr => createPersonRequest(rhr.customer,rhr.pickUpLocationUTM,tick,rhr.destinationUTM))
      val customerIdToReqs = toPool.map(rhr => rhr.customer.personId -> rhr).toMap
      val availVehicles = rideHailManager.vehicleManager.availableRideHailVehicles.values.map(veh => createVehicleAndSchedule(veh.vehicleId.toString,veh.currentLocationUTM.loc,tick))

      val assignment = if(true){
        val algo = new AlonsoMoraPoolingAlgForRideHail(
          customerReqs.toList,
          availVehicles.toList,
          omega = 6 * 60,
          delta = 10 * 5000 * 60,
          radius = Int.MaxValue,
          skimmer
        )
        logger.info("PairwiseGraph")
        val rvGraph: RVGraph = algo.pairwiseRVGraph
        logger.info("RTVGraph")
        val rtvGraph = algo.rTVGraph(rvGraph)
        logger.info("Greedy")
        algo.greedyAssignment(rtvGraph)
      }else{
        val algo = new AsyncAlonsoMoraAlgForRideHail(
          customerReqs.toList,
          availVehicles.toList,
          Map[MobilityServiceRequestType, Int]((Pickup, 6 * 60), (Dropoff, 10 * 5000 * 60)),
          radius = Int.MaxValue,
          skimmer
        )
        logger.info("Assigning")
        import scala.concurrent.duration._
        Await.result(algo.greedyAssignment(), atMost = 2.minutes)
      }
      logger.info("Result: {} assigned trips",assignment.size)

      assignment.foreach{ case (theTrip,vehicleAndSchedule,cost) =>
        alreadyAllocated = alreadyAllocated + vehicleAndSchedule.vehicle.id
        var newRideHailRequest: Option[RideHailRequest] = None
        var scheduleToCache: List[MobilityServiceRequest] = List()
        val rReqs = theTrip.schedule.tail.
          sliding(2)
          .flatMap{ wayPoints =>
            val orig = wayPoints(0)
            val dest = wayPoints(1)
            val origin = SpaceTime(orig.activity.getCoord, orig.serviceTime.toInt)
            if(newRideHailRequest.isEmpty){
              newRideHailRequest = Some(customerIdToReqs(orig.person.get.personId))
            }else if(!newRideHailRequest.get.customer.equals(orig.person.get) && newRideHailRequest.get.groupedWithOtherRequests.find(_.customer.equals(orig.person.get)).isEmpty){
              newRideHailRequest = Some(newRideHailRequest.get.addSubRequest(customerIdToReqs(orig.person.get.personId)))
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
        tempScheduleStore.put(newRideHailRequest.get.requestId,scheduleToCache :+ theTrip.schedule.last)
      }
    }
//    if(allocResponses.size>0 ){
//      if(allocResponses.find{
//        case RoutingRequiredToAllocateVehicle(req,routes) =>
//          false
//        case VehicleMatchedToCustomers(_,_,pickdrops) =>
//          pickdrops.filter(_.leg.isDefined).size>2
//        case _ =>
//          false
//      }.isDefined){
////      if(tick>=21900){
//        val i = 0
//      }
//    }
    logger.info("AllocResponses: {}",allocResponses.groupBy(_.getClass).map(x => s"${x._1.getSimpleName} -- ${x._2.size}").mkString("\t"))
    VehicleAllocations(allocResponses)
  }


}
