package beam.agentsim.agents.ridehail

import java.util
import java.util.concurrent.TimeUnit

import akka.actor.{ActorLogging, ActorRef, Props}
import akka.pattern._
import akka.util.Timeout
import beam.agentsim
import beam.agentsim.Resource._
import beam.agentsim.ResourceManager.{NotifyVehicleResourceIdle, VehicleManager}
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.HasTickAndTrigger
import beam.agentsim.agents.modalbehaviors.DrivesVehicle._
import beam.agentsim.agents.ridehail.RideHailAgent._
import beam.agentsim.agents.ridehail.RideHailIterationHistoryActor.GetCurrentIterationRideHailStats
import beam.agentsim.agents.ridehail.RideHailManager._
import beam.agentsim.agents.ridehail.allocation._
import beam.agentsim.agents.vehicles.AccessErrorCodes.{
  CouldNotFindRouteToCustomer,
  DriverNotFoundError,
  RideHailVehicleTakenError
}
import beam.agentsim.agents.vehicles.BeamVehicle.BeamVehicleState
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.agents.vehicles.{PassengerSchedule, _}
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.ParkingManager.{DepotParkingInquiry, DepotParkingInquiryResponse}
import beam.agentsim.infrastructure.ParkingStall
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.router.BeamRouter.{Location, RoutingRequest, RoutingResponse, _}
import beam.router.Modes.BeamMode._
import beam.router.model.EmbodiedBeamTrip
import beam.router.model.RoutingModel.DiscreteTime
import beam.sim.{BeamServices, HasServices}
import beam.utils.{DebugLib, PointToPlot, SpatialPlot}
import com.eaio.uuid.UUIDGen
import com.google.common.cache.{Cache, CacheBuilder}
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.collections.QuadTree
import org.matsim.core.utils.geometry.CoordUtils
import org.matsim.vehicles.Vehicle

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.concurrent.{Await, Future}

object RideHailAgentLocationWithRadiusOrdering extends Ordering[(RideHailAgentLocation, Double)] {
  override def compare(
    o1: (RideHailAgentLocation, Double),
    o2: (RideHailAgentLocation, Double)
  ): Int = {
    java.lang.Double.compare(o1._2, o2._2)
  }
}

object RideHailManager {

  val dummyID: Id[RideHailRequest] =
    Id.create("dummyInquiryId", classOf[RideHailRequest])

  val INITIAL_RIDE_HAIL_LOCATION_HOME = "HOME"
  val INITIAL_RIDE_HAIL_LOCATION_UNIFORM_RANDOM = "UNIFORM_RANDOM"
  val INITIAL_RIDE_HAIL_LOCATION_ALL_AT_CENTER = "ALL_AT_CENTER"
  val INITIAL_RIDE_HAIL_LOCATION_ALL_IN_CORNER = "ALL_IN_CORNER"
  val dummyRideHailVehicleId: Id[Vehicle] = Id.createVehicleId("dummyRideHailVehicle")

  def nextRideHailInquiryId: Id[RideHailRequest] = {
    Id.create(UUIDGen.createTime(UUIDGen.newTime()).toString, classOf[RideHailRequest])
  }

  def props(
    services: BeamServices,
    scheduler: ActorRef,
    router: ActorRef,
    parkingManager: ActorRef,
    boundingBox: Envelope,
    surgePricingManager: RideHailSurgePricingManager
  ): Props = {
    Props(
      new RideHailManager(
        services,
        scheduler,
        router,
        parkingManager,
        boundingBox,
        surgePricingManager
      )
    )
  }

  sealed trait RideHailServiceStatus

  case class NotifyIterationEnds()

  case class TravelProposal(
    rideHailAgentLocation: RideHailAgentLocation,
    responseRideHail2Pickup: RoutingResponse,
    responseRideHail2Dest: RoutingResponse,
    poolingInfo: Option[PoolingInfo] = None
  ) {
    lazy val timeToCustomer = responseRideHail2Pickup.itineraries.map(_.totalTravelTimeInSecs).sum
    lazy val estimatedPrice = responseRideHail2Dest.itineraries.headOption.map(_.costEstimate).getOrElse(0.0)
    lazy val estimatedTravelTime =
      responseRideHail2Dest.itineraries.headOption.map(_.totalTravelTimeInSecs).getOrElse(0.0)
    override def toString: String =
      s"RHA: ${rideHailAgentLocation.vehicleId}, waitTime: $timeToCustomer, price: $estimatedPrice, travelTime: $estimatedTravelTime"
  }

  case class RoutingResponses(
    routingResponses: List[RoutingResponse]
  )

  case class PoolingInfo(timeFactor: Double, costFactor: Double)

  case class RegisterRideAvailable(
    rideHailAgent: ActorRef,
    vehicleId: Id[Vehicle],
    availableSince: SpaceTime
  )

  case class RegisterRideUnavailable(ref: ActorRef, location: Coord)

  case class RideHailAgentLocation(
    rideHailAgent: ActorRef,
    vehicleId: Id[Vehicle],
    currentLocation: SpaceTime
  ) {

    def toStreetVehicle: StreetVehicle = {
      StreetVehicle(vehicleId, currentLocation, CAR, asDriver = true)
    }
  }

  case class RideHailAgentETA(
    agentLocation: RideHailAgentLocation,
    distance: Double,
    timeToCustomer: Double
  )

  case class RepositionResponse(
    rnd1: RideHailAgentLocation,
    rnd2: RideHailManager.RideHailAgentLocation,
    rnd1Response: RoutingResponse,
    rnd2Response: RoutingResponse
  )

  case class BufferedRideHailRequestsTrigger(tick: Int) extends Trigger

  case class RideHailRepositioningTrigger(tick: Int) extends Trigger

  case object RideUnavailableAck

  case object RideAvailableAck

  case object DebugRideHailManagerDuringExecution

  case object ContinueBufferedRideHailRequests

  /* Available means vehicle can be assigned to a new customer */
  case object Available extends RideHailServiceStatus

  case object InService extends RideHailServiceStatus

  case object OutOfService extends RideHailServiceStatus

}

// TODO: RW: We need to update the location of vehicle as it is moving to give good estimate to ride hail allocation manager
// TODO: Build RHM from XML to be able to specify different kinds of TNC/Rideshare types and attributes
// TODO: remove name variable, as not used currently in the code anywhere?

class RideHailManager(
  val beamServices: BeamServices,
  val scheduler: ActorRef,
  val router: ActorRef,
  val parkingManager: ActorRef,
  val boundingBox: Envelope,
  val surgePricingManager: RideHailSurgePricingManager
) extends VehicleManager
    with ActorLogging
    with HasServices
    with HasTickAndTrigger {

  implicit val timeout: Timeout = Timeout(50000, TimeUnit.SECONDS)

  /**
    * Customer inquiries awaiting reservation confirmation.
    */
  lazy val travelProposalCache: Cache[String, TravelProposal] = {
    CacheBuilder
      .newBuilder()
      .maximumSize(
        (10 * beamServices.beamConfig.beam.agentsim.agents.rideHail.numDriversAsFractionOfPopulation * beamServices.beamConfig.beam.agentsim.numAgents).toLong
      )
      .expireAfterWrite(1, TimeUnit.MINUTES)
      .build()
  }
  override val resources: mutable.Map[Id[BeamVehicle], BeamVehicle] =
    mutable.Map[Id[BeamVehicle], BeamVehicle]()

  val radiusInMeters: Double =
    beamServices.beamConfig.beam.agentsim.agents.rideHail.rideHailManager.radiusInMeters

  val rideHailNetworkApi: RideHailNetworkAPI = new RideHailNetworkAPI()
  val processBufferedRequestsOnTimeout = beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.requestBufferTimeoutInSeconds > 0

  val tncIterationStats: Option[TNCIterationStats] = {
    val rideHailIterationHistoryActor =
      context.actorSelection("/user/rideHailIterationHistoryActor")
    val future =
      rideHailIterationHistoryActor.ask(GetCurrentIterationRideHailStats)
    Await
      .result(future, Timeout(60, TimeUnit.SECONDS).duration)
      .asInstanceOf[Option[TNCIterationStats]]
  }
  private val rideHailResourceAllocationManager = RideHailResourceAllocationManager(
    beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.name,
    this
  )

  val modifyPassengerScheduleManager =
    new RideHailModifyPassengerScheduleManager(
      log,
      self,
      scheduler,
      beamServices.beamConfig
    )
  private val outOfServiceVehicleManager =
    new OutOfServiceVehicleManager(
      log,
      self,
      this
    )
  private val vehicleState: mutable.Map[Id[Vehicle], BeamVehicleState] =
    mutable.Map[Id[Vehicle], BeamVehicleState]()
  private val DefaultCostPerMinute = beamServices.beamConfig.beam.agentsim.agents.rideHail.defaultCostPerMinute
  tncIterationStats.foreach(_.logMap())
  private val DefaultCostPerSecond = DefaultCostPerMinute / 60.0d

  beamServices.beamRouter ! GetTravelTime
  beamServices.beamRouter ! GetMatSimNetwork
  //TODO improve search to take into account time when available
  private val availableRideHailAgentSpatialIndex = {
    new QuadTree[RideHailAgentLocation](
      boundingBox.getMinX,
      boundingBox.getMinY,
      boundingBox.getMaxX,
      boundingBox.getMaxY
    )
  }
  private val inServiceRideHailAgentSpatialIndex = {
    new QuadTree[RideHailAgentLocation](
      boundingBox.getMinX,
      boundingBox.getMinY,
      boundingBox.getMaxX,
      boundingBox.getMaxY
    )
  }
  private val outOfServiceRideHailAgentSpatialIndex = {
    new QuadTree[RideHailAgentLocation](
      boundingBox.getMinX,
      boundingBox.getMinY,
      boundingBox.getMaxX,
      boundingBox.getMaxY
    )
  }
  private val availableRideHailVehicles = mutable.HashMap[Id[Vehicle], RideHailAgentLocation]()
  private val outOfServiceRideHailVehicles = mutable.HashMap[Id[Vehicle], RideHailAgentLocation]()
  private val inServiceRideHailVehicles = mutable.HashMap[Id[Vehicle], RideHailAgentLocation]()
  private val pendingModifyPassengerScheduleAcks = mutable.HashMap[String, RideHailResponse]()
  private val parkingInquiryCache = collection.mutable.HashMap[Int, RideHailAgentLocation]()
  private val pendingAgentsSentToPark = collection.mutable.Map[Id[Vehicle], ParkingStall]()
  private var lockedVehicles = Set[Id[Vehicle]]()

  // Tracking Inquiries and Reservation Requests
  val inquiryIdToInquiryAndResponse: mutable.Map[Int, (RideHailRequest, SingleOccupantQuoteAndPoolingInfo)] =
    mutable.Map()
  val routeRequestIdToRideHailRequestId: mutable.Map[Int, Int] = mutable.Map()
  val reservationIdToRequest: mutable.Map[Int, RideHailRequest] = mutable.Map()
  var allTriggersToScheduleForBufferedReservations: Vector[ScheduleTrigger] = Vector()

  //context.actorSelection("user/")
  //rideHailIterationHistoryActor send message to ridheailiterationhsitoryactor

  DebugLib.emptyFunctionForSettingBreakPoint()

  override def receive: Receive = {
    case ev @ StopDrivingIfNoPassengerOnBoardReply(success, requestId, tick) =>
      Option(travelProposalCache.getIfPresent(requestId.toString)) match {
        case Some(travelProposal) =>
          if (success) {
            travelProposal.rideHailAgentLocation.rideHailAgent ! StopDriving(tick)
            travelProposal.rideHailAgentLocation.rideHailAgent ! Resume()
          }
          rideHailResourceAllocationManager.handleRideCancellationReply(ev)

        case None =>
          log.error("request not found: {}", ev)
      }

    case NotifyIterationEnds() =>
      surgePricingManager.incrementIteration()
      sender ! Unit // return empty object to blocking caller

    case RegisterResource(vId) =>
      val vehId = vId.asInstanceOf[Id[Vehicle]]
      resources.put(agentsim.vehicleId2BeamVehicleId(vehId), beamServices.vehicles(vehId))

    case ev @ NotifyVehicleResourceIdle(
          vId,
          whenWhereOpt,
          passengerSchedule,
          beamVehicleState,
          triggerId
        ) =>
      log.debug("RHM.NotifyVehicleResourceIdle: {}", ev)
      val vehicleId = vId.asInstanceOf[Id[Vehicle]]
      val whenWhere = whenWhereOpt.getOrElse(getRideHailAgentLocation(vehicleId).currentLocation)

      updateLocationOfAgent(vehicleId, whenWhere, getServiceStatusOf(vehicleId))

      //updateLocationOfAgent(vehicleId, whenWhereOpt, isAvailable = true)
      resources.get(agentsim.vehicleId2BeamVehicleId(vehicleId)).foreach { beamVehicle =>
        beamVehicle.driver.foreach { driver =>
          val rideHailAgentLocation =
            RideHailAgentLocation(driver, vehicleId, whenWhere)
          vehicleState.put(vehicleId, beamVehicleState)

          if (modifyPassengerScheduleManager
                .noPendingReservations(vehicleId) || modifyPassengerScheduleManager
                .isPendingReservationEnding(vehicleId, passengerSchedule)) {

            log.debug("range: {}", beamVehicleState.remainingRangeInM / 1000.0)
            val stallOpt = pendingAgentsSentToPark.remove(vehicleId)
            if (stallOpt.isDefined) {
              log.debug("Initiate refuel session for vehicle: {}", vehicleId)
              // this agent has arrived to refuel, initiate that session
              val startFuelTrigger = ScheduleTrigger(
                StartRefuelTrigger(whenWhere.time),
                rideHailAgentLocation.rideHailAgent
              )
              beamServices.vehicles(rideHailAgentLocation.vehicleId).useParkingStall(stallOpt.get)
              sender() ! NotifyVehicleResourceIdleReply(
                triggerId,
                Vector[ScheduleTrigger](startFuelTrigger)
              )
            } else if (beamVehicleState.remainingRangeInM < beamServices.beamConfig.beam.agentsim.agents.rideHail.refuelThresholdInMeters) {
              // not enough range to make trip

              if (modifyPassengerScheduleManager.vehicleHasMoreThanOneOngoingRequests(vehicleId)) {
                putOutOfService(rideHailAgentLocation)
                sender() ! NotifyVehicleResourceIdleReply(triggerId, Vector[ScheduleTrigger]())
              } else {
                log.debug("Not enough range: {}", vehicleId)
                outOfServiceVehicleManager.registerTrigger(vehicleId, triggerId)
                putOutOfService(rideHailAgentLocation)
                findRefuelStationAndSendVehicle(rideHailAgentLocation)
              }
            } else {
              log.debug("Making available: {}", vehicleId)
              makeAvailable(rideHailAgentLocation)
              // ridehail agent awaiting NotifyVehicleResourceIdleReply
              sender() ! NotifyVehicleResourceIdleReply(triggerId, Vector[ScheduleTrigger]())
            }
          } else {
            // ridehail agent awaiting NotifyVehicleResourceIdleReply
            sender() ! NotifyVehicleResourceIdleReply(triggerId, Vector[ScheduleTrigger]())
          }
          modifyPassengerScheduleManager
            .checkInResource(vehicleId, Some(whenWhere), Some(passengerSchedule))
        }
      }

    case NotifyResourceInUse(vId, whenWhere) =>
      val vehId = vId.asInstanceOf[Id[Vehicle]]
      updateLocationOfAgent(vehId, whenWhere, InService)

    case BeamVehicleStateUpdate(id, beamVehicleState) =>
      vehicleState.put(id, beamVehicleState)

    case MATSimNetwork(network) =>
      rideHailNetworkApi.setMATSimNetwork(network)

    case CheckInResource(vId, whenWhere) =>
      val vehicleId = vId.asInstanceOf[Id[Vehicle]]
      updateLocationOfAgent(vehicleId, whenWhere.get, Available)

      if (whenWhere.get.time == 0) {
        resources(agentsim.vehicleId2BeamVehicleId(vehicleId)).driver
          .foreach(driver => {
            val rideHailAgentLocation =
              RideHailAgentLocation(driver, vehicleId, whenWhere.get)
            if (modifyPassengerScheduleManager
                  .noPendingReservations(vehicleId)) {
              log.debug("Checking in: {}", vehicleId)
              makeAvailable(rideHailAgentLocation)
            }
            sender ! CheckInSuccess
            log.debug(
              "checking in resource: vehicleId({});availableIn.time({})",
              vehicleId,
              whenWhere.get.time
            )
            modifyPassengerScheduleManager.checkInResource(vehicleId, whenWhere, None)
            driver ! GetBeamVehicleState
          })
      }

    case CheckOutResource(_) =>
      // Because the RideHail Manager is in charge of deciding which specific vehicles to assign to customers, this should never be used
      throw new RuntimeException(
        "Illegal use of CheckOutResource, RideHailManager is responsible for checking out vehicles in fleet."
      )

    case inquiry @ RideHailRequest(RideHailInquiry, _, _, _, _, _) =>
      handleRideHailInquiry(inquiry)

    case R5Network(network) =>
      rideHailNetworkApi.setR5Network(network)

    /*
     * In the following case, we are calculating routes in batch for the allocation manager,
     * so we add these to the allocation buffer and then resume the allocation process.
     */
    case RoutingResponses(responses)
        if reservationIdToRequest.contains(routeRequestIdToRideHailRequestId(responses.head.staticRequestId)) =>
      responses.foreach { routeResponse =>
        val request = reservationIdToRequest(routeRequestIdToRideHailRequestId(routeResponse.staticRequestId))
        rideHailResourceAllocationManager.addRouteForRequestToBuffer(request, routeResponse)
      }
      self ! ContinueBufferedRideHailRequests

    /*
     * Routing Responses from a Ride Hail Inquiry
     * In this case we can treat the responses as if they apply to a single request
     * for a single occupant trip.
     */
    case RoutingResponses(responses)
        if inquiryIdToInquiryAndResponse.contains(routeRequestIdToRideHailRequestId(responses.head.staticRequestId)) =>
      val (request, singleOccupantQuoteAndPoolingInfo) = inquiryIdToInquiryAndResponse(
        routeRequestIdToRideHailRequestId(responses.head.staticRequestId)
      )

      // We can rely on preserved ordering here (see RideHailManager.requestRoutes),
      // for a simple single-occupant trip sequence, we know that first
      // itin is RH2Customer and second is Pickup2Destination.
      // TODO generalize the processing below to allow for pooling
      val itins = responses.map { response =>
        response.itineraries.filter(p => p.tripClassifier.equals(RIDE_HAIL)).head
      }
      if (itins.size == 2) {
        val itin2Cust = itins.head
        val itin2Dest = itins.last

        val rideHailFarePerSecond = DefaultCostPerSecond * surgePricingManager
          .getSurgeLevel(
            request.pickUpLocation,
            request.departAt.atTime.toDouble
          )
        val customerCost = rideHailFarePerSecond * itin2Dest.totalTravelTimeInSecs

        val tripDriver2Cust = RoutingResponse(
          Vector(
            itin2Cust.copy(legs = itin2Cust.legs.map(l => l.copy(asDriver = true)))
          ),
          java.util.UUID.randomUUID().hashCode()
        )
        val timeToCustomer =
          tripDriver2Cust.itineraries.head.totalTravelTimeInSecs

        val tripCust2Dest = RoutingResponse(
          Vector(
            itin2Dest.copy(
              legs = itin2Dest.legs.map(
                leg =>
                  leg.copy(
                    beamLeg = leg.beamLeg.updateStartTime(leg.beamLeg.startTime + timeToCustomer),
                    asDriver = false,
                    cost = customerCost,
                    unbecomeDriverOnCompletion = false
                )
              )
            )
          ),
          java.util.UUID.randomUUID().hashCode()
        )
        val travelProposal = TravelProposal(
          singleOccupantQuoteAndPoolingInfo.rideHailAgentLocation,
          tripDriver2Cust,
          tripCust2Dest,
          singleOccupantQuoteAndPoolingInfo.poolingInfo
        )
        travelProposalCache.put(request.requestId.toString, travelProposal)

        request.customer.personRef.get ! RideHailResponse(request, Some(travelProposal))
      } else {
        log.debug(
          "Router could not find route to customer person={} for requestId={}",
          request.customer.personId,
          request.requestId
        )
        request.customer.personRef.get ! RideHailResponse(
          request,
          None,
          Some(CouldNotFindRouteToCustomer)
        )
      }
      inquiryIdToInquiryAndResponse.remove(request.requestId)
      responses.foreach(rResp => routeRequestIdToRideHailRequestId.remove(rResp.staticRequestId))

    case reserveRide @ RideHailRequest(ReserveRide, _, _, _, _, _) =>
      handleReservationRequest(reserveRide)

    case modifyPassengerScheduleAck @ ModifyPassengerScheduleAck(
          requestIdOpt,
          triggersToSchedule,
          vehicleId
        ) =>
      pendingAgentsSentToPark.get(vehicleId) match{
        case Some(_) =>
          log.debug("modifyPassengerScheduleAck received, handling with outOfServiceManager {}",modifyPassengerScheduleAck)
          outOfServiceVehicleManager.releaseTrigger(vehicleId, triggersToSchedule)
        case None =>
          requestIdOpt match {
            case None =>
              // None here means this is part of repositioning, i.e. not tied to a reservation request
              log.debug("modifyPassengerScheduleAck received, handling with modifyPassengerScheduleManager {}", modifyPassengerScheduleAck)
              modifyPassengerScheduleManager
                .modifyPassengerScheduleAckReceivedForRepositioning(
                  triggersToSchedule
                )
            case Some(requestId) =>
              // Some here means this is part of a reservation / dispatch of vehicle to a customer
              log.debug("modifyPassengerScheduleAck received, completing reservation {}", modifyPassengerScheduleAck)
              completeReservation(requestId, triggersToSchedule)
          }
      }

    case UpdateTravelTimeLocal(travelTime) =>
      rideHailNetworkApi.setTravelTime(travelTime)

    case DebugRideHailManagerDuringExecution =>
      modifyPassengerScheduleManager.printState()

    case TriggerWithId(BufferedRideHailRequestsTrigger(tick), triggerId) =>
      holdTickAndTriggerId(tick, triggerId)
      findAllocationsAndProcess(tick)

    case ContinueBufferedRideHailRequests =>
      findAllocationsAndProcess(_currentTick.getOrElse(0))

    case TriggerWithId(RideHailRepositioningTrigger(tick), triggerId) =>
//      DebugRepositioning.produceRepositioningDebugImages(tick, this)

      modifyPassengerScheduleManager.startWaveOfRepositioningRequests(tick, triggerId)

      //      log.debug("getIdleVehicles().size:{}", getIdleVehicles.size)
      //      getIdleVehicles.foreach(x => log.debug("getIdleVehicles(): {}", x._1.toString))

      val repositionVehicles: Vector[(Id[Vehicle], Location)] =
        rideHailResourceAllocationManager.repositionVehicles(tick)

      if (repositionVehicles.isEmpty) {
        modifyPassengerScheduleManager
          .sendoutAckMessageToSchedulerForRideHailAllocationmanagerTimeout()
      } else {
        modifyPassengerScheduleManager.setNumberOfRepositioningsToProcess(repositionVehicles.size)
        //   printRepositionDistanceSum(repositionVehicles)
      }

      for ((vehicleId, destinationLocation) <- repositionVehicles) {
        if (getIdleVehicles.contains(vehicleId)) {
          val rideHailAgentLocation = getIdleVehicles(vehicleId)

          val rideHailVehicleAtOrigin = StreetVehicle(
            rideHailAgentLocation.vehicleId,
            SpaceTime((rideHailAgentLocation.currentLocation.loc, tick)),
            CAR,
            asDriver = false
          )
          val routingRequest = RoutingRequest(
            origin = rideHailAgentLocation.currentLocation.loc,
            destination = destinationLocation,
            departureTime = DiscreteTime(tick.toInt),
            transitModes = Vector(),
            streetVehicles = Vector(rideHailVehicleAtOrigin)
          )
          val futureRideHailAgent2CustomerResponse = router ? routingRequest

          for {
            rideHailAgent2CustomerResponse <- futureRideHailAgent2CustomerResponse
              .mapTo[RoutingResponse]
          } {
            val itins2Cust = rideHailAgent2CustomerResponse.itineraries.filter(
              x => x.tripClassifier.equals(RIDE_HAIL)
            )

            if (itins2Cust.nonEmpty) {
              val modRHA2Cust: IndexedSeq[EmbodiedBeamTrip] =
                itins2Cust.map(l => l.copy(legs = l.legs.map(c => c.copy(asDriver = true)))).toIndexedSeq
              val rideHailAgent2CustomerResponseMod = RoutingResponse(modRHA2Cust, routingRequest.staticRequestId)

              // TODO: extract creation of route to separate method?
              val passengerSchedule = PassengerSchedule().addLegs(
                rideHailAgent2CustomerResponseMod.itineraries.head.toBeamTrip.legs
              )
              self ! RepositionVehicleRequest(passengerSchedule, tick, vehicleId, rideHailAgentLocation.rideHailAgent)
            } else {
              self ! ReduceAwaitingRepositioningAckMessagesByOne
            }
          }

        } else {
          modifyPassengerScheduleManager
            .modifyPassengerScheduleAckReceivedForRepositioning(
              Vector()
            )
        }
      }

    case ReduceAwaitingRepositioningAckMessagesByOne =>
      modifyPassengerScheduleManager
        .modifyPassengerScheduleAckReceivedForRepositioning(Vector())

    case MoveOutOfServiceVehicleToDepotParking(passengerSchedule, tick, vehicleId, stall) =>
      pendingAgentsSentToPark.put(vehicleId, stall)
      outOfServiceVehicleManager.initiateMovementToParkingDepot(vehicleId, passengerSchedule, tick)

    case RepositionVehicleRequest(passengerSchedule, tick, vehicleId, rideHailAgent) =>
      if (getIdleVehicles.contains(vehicleId)) {
        //        log.debug(
        //          "RideHailAllocationManagerTimeout: requesting to send interrupt message to vehicle for repositioning: {}",
        //          vehicleId
        //        )
        modifyPassengerScheduleManager.repositionVehicle(
          passengerSchedule,
          tick,
          vehicleId,
          rideHailAgent
        )
      } else {
        // Failed attempt to reposition a car that is no longer idle
        modifyPassengerScheduleManager.cancelRepositionAttempt()
      }

    case reply @ InterruptedWhileIdle(interruptId, vehicleId, tick) =>
      if (pendingAgentsSentToPark.contains(vehicleId)) {
        outOfServiceVehicleManager.handleInterruptReply(vehicleId)
      } else {
        modifyPassengerScheduleManager.handleInterruptReply(reply)
      }

    case reply @ InterruptedWhileDriving(interruptId,vehicleId, tick, interruptedPassengerSchedule, _) =>
      if (pendingAgentsSentToPark.contains(vehicleId)) {
        log.error(
          "It is not expected in the current implementation that a moving vehicle would be stopped and sent for charging"
        )
      } else {
        modifyPassengerScheduleManager.handleInterruptReply(reply)
      }

    case DepotParkingInquiryResponse(None, requestId) =>
      val vehId = parkingInquiryCache(requestId).vehicleId
      log.warning(
        "No parking stall found, ride hail vehicle {} stranded",
        vehId
      )
      outOfServiceVehicleManager.releaseTrigger(vehId, Vector())

    case DepotParkingInquiryResponse(Some(stall), requestId) =>
      val agentLocation = parkingInquiryCache.remove(requestId).get

      val routingRequest = RoutingRequest(
        origin = agentLocation.currentLocation.loc,
        destination = stall.location,
        departureTime = DiscreteTime(agentLocation.currentLocation.time),
        transitModes = Vector(),
        streetVehicles = Vector(agentLocation.toStreetVehicle)
      )
      val futureRideHail2ParkingRouteRequest = router ? routingRequest

      for {
        futureRideHail2ParkingRouteRespones <- futureRideHail2ParkingRouteRequest
          .mapTo[RoutingResponse]
      } {
        val itinOpt = futureRideHail2ParkingRouteRespones.itineraries
          .find(x => x.tripClassifier.equals(RIDE_HAIL))

        itinOpt match {
          case Some(itin) =>
            val passengerSchedule = PassengerSchedule().addLegs(
              itin.toBeamTrip.legs
            )
            self ! MoveOutOfServiceVehicleToDepotParking(
              passengerSchedule,
              itin.legs.head.beamLeg.startTime,
              agentLocation.vehicleId,
              stall
            )
          case None =>
            //log.error(
            //  "No route to parking stall found, ride hail agent {} stranded",
            //  agentLocation.vehicleId
            //)

            // release trigger if no parking depot found so that simulation can continue
            self ! ReleaseAgentTrigger(agentLocation.vehicleId)
        }
      }

    case ReleaseAgentTrigger(vehicleId) =>
      outOfServiceVehicleManager.releaseTrigger(vehicleId)

    case Finish =>
      log.info("finish message received from BeamAgentScheduler")

    case msg =>
      log.warning("unknown message received by RideHailManager {}", msg)

  }

  def findRefuelStationAndSendVehicle(rideHailAgentLocation: RideHailAgentLocation): Unit = {
    val inquiry = DepotParkingInquiry(
      rideHailAgentLocation.vehicleId,
      rideHailAgentLocation.currentLocation.loc,
      ParkingStall.RideHailManager
    )
    parkingInquiryCache.put(inquiry.requestId, rideHailAgentLocation)
    parkingManager ! inquiry
  }

  def scheduleNextBufferedTrigger(triggersToSchedule: Vector[ScheduleTrigger] = Vector()) = {
    val (tick, triggerId) = releaseTickAndTriggerId()
    val timerTrigger = ScheduleTrigger(
      BufferedRideHailRequestsTrigger(
        tick + beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.requestBufferTimeoutInSeconds
      ),
      self
    )
    scheduler ! CompletionNotice(triggerId, triggersToSchedule :+ timerTrigger)
  }

  def handleRideHailInquiry(inquiry: RideHailRequest): Unit = {
    rideHailResourceAllocationManager.respondToInquiry(inquiry) match {
      case NoVehiclesAvailable =>
        log.debug("{} -- NoVehiclesAvailable", inquiry.requestId)
        inquiry.customer.personRef.get ! RideHailResponse(inquiry, None, Some(DriverNotFoundError))
      case inquiryResponse @ SingleOccupantQuoteAndPoolingInfo(agentLocation, None, poolingInfo) =>
        inquiryIdToInquiryAndResponse.put(inquiry.requestId, (inquiry, inquiryResponse))
        val routingRequests = createRoutingRequestsToCustomerAndDestination(inquiry, agentLocation)
        routingRequests.foreach(rReq => routeRequestIdToRideHailRequestId.put(rReq.staticRequestId, inquiry.requestId))
        requestRoutes(routingRequests)
    }
  }

  def getRideHailAgentLocation(vehicleId: Id[Vehicle]): RideHailAgentLocation = {
    getServiceStatusOf(vehicleId) match {
      case Available =>
        availableRideHailVehicles(vehicleId)
      case InService =>
        inServiceRideHailVehicles(vehicleId)
      case OutOfService =>
        outOfServiceRideHailVehicles(vehicleId)
    }
  }

  def getClosestIdleVehiclesWithinRadiusByETA(
    pickupLocation: Coord,
    radius: Double,
    customerRequestTime: Long,
    secondsPerEuclideanMeterFactor: Double = 0.1 // (~13.4m/s)^-1 * 1.4
  ): Vector[RideHailAgentETA] = {
    var start = System.currentTimeMillis()
    val nearbyAvailableRideHailAgents = availableRideHailAgentSpatialIndex
      .getDisk(pickupLocation.getX, pickupLocation.getY, radius)
      .asScala
      .filter(x => availableRideHailVehicles.contains(x.vehicleId))
    var end = System.currentTimeMillis()
    val diff1 = end - start

    start = System.currentTimeMillis()
    val times2RideHailAgents = nearbyAvailableRideHailAgents
      .map { rideHailAgentLocation =>
        val distance =
          CoordUtils.calcProjectedEuclideanDistance(pickupLocation, rideHailAgentLocation.currentLocation.loc)
        // we consider the time to travel to the customer and the time before the vehicle is actually ready (due to
        // already moving or dropping off a customer, etc.)
        val extra = Math.max(rideHailAgentLocation.currentLocation.time - customerRequestTime, 0)
        val timeToCustomer = distance * secondsPerEuclideanMeterFactor + extra
        RideHailAgentETA(rideHailAgentLocation, distance, timeToCustomer)
      }
      .toVector
      .sortBy(_.timeToCustomer)
    end = System.currentTimeMillis()
    val diff2 = end - start

    if (diff1 + diff2 > 100)
      log.debug(
        s"getClosestIdleVehiclesWithinRadiusByETA for $pickupLocation with $radius nearbyAvailableRideHailAgents: $diff1, diff2: $diff2. Total: ${diff1 + diff2} ms"
      )

    times2RideHailAgents
  }

  def getClosestIdleVehiclesWithinRadius(
    pickupLocation: Coord,
    radius: Double
  ): Array[RideHailAgentLocation] = {
    val idleVehicles = getIdleVehiclesWithinRadius(pickupLocation, radius).toArray
    util.Arrays.sort(idleVehicles, RideHailAgentLocationWithRadiusOrdering)
    idleVehicles.map { case (location, _) => location }
  }

  def getIdleVehiclesWithinRadius(
    pickupLocation: Location,
    radius: Double
  ): Iterable[(RideHailAgentLocation, Double)] = {
    val nearbyRideHailAgents = availableRideHailAgentSpatialIndex
      .getDisk(pickupLocation.getX, pickupLocation.getY, radius)
      .asScala
      .view
    val distances2RideHailAgents =
      nearbyRideHailAgents.map(rideHailAgentLocation => {
        val distance = CoordUtils
          .calcProjectedEuclideanDistance(pickupLocation, rideHailAgentLocation.currentLocation.loc)
        (rideHailAgentLocation, distance)
      })
    distances2RideHailAgents.filter(x => availableRideHailVehicles.contains(x._1.vehicleId))
  }

  def getClosestIdleRideHailAgent(
    pickupLocation: Coord,
    radius: Double
  ): Option[RideHailAgentLocation] = {
    val idleVehicles = getIdleVehiclesWithinRadius(pickupLocation, radius)
    if (idleVehicles.isEmpty) None
    else {
      val min = idleVehicles.min(RideHailAgentLocationWithRadiusOrdering)
      Some(min._1)
    }
  }

  def attemptToCancelCurrentRideRequest(tick: Int, requestId: Int): Unit = {
    Option(travelProposalCache.getIfPresent(requestId.toString)) match {
      case Some(travelProposal) =>
        log.debug(
          "trying to stop vehicle: {}, tick: {}",
          travelProposal.rideHailAgentLocation.vehicleId,
          tick
        )
        travelProposal.rideHailAgentLocation.rideHailAgent ! StopDrivingIfNoPassengerOnBoard(
          tick,
          requestId
        )

      case None =>
    }

  }

  def unlockVehicle(vehicleId: Id[Vehicle]): Unit = {
    lockedVehicles -= vehicleId
  }

  def lockVehicle(vehicleId: Id[Vehicle]): Unit = {
    lockedVehicles += vehicleId
  }

  def getVehicleState(vehicleId: Id[Vehicle]): BeamVehicleState =
    vehicleState(vehicleId)

  def cleanCurrentPickupAssignment(request: RideHailRequest): Unit = {
//vehicleAllocationRequest.request, vehicleId: Id[Vehicle], tick:Double

    val tick = 0.0 // TODO: get tick of timeout here

    Option(travelProposalCache.getIfPresent(request.requestId.toString)) match {
      case Some(travelProposal) =>
        if (inServiceRideHailVehicles.contains(travelProposal.rideHailAgentLocation.vehicleId) ||
            lockedVehicles.contains(travelProposal.rideHailAgentLocation.vehicleId)) {
          // TODO: this creates friction with the interrupt Id -> go through the passenger schedule manager?
          travelProposal.rideHailAgentLocation.rideHailAgent ! Interrupt(
            Id.create(travelProposal.rideHailAgentLocation.vehicleId.toString, classOf[Interrupt]),
            tick
          )
        } else {
          // TODO: provide input to caller to change option resp. test this?
        }
      case None =>
      // TODO: provide input to caller to change option resp. test this?
    }

  }

  def createRoutingRequestsToCustomerAndDestination(
    request: RideHailRequest,
    rideHailLocation: RideHailAgentLocation
  ): List[RoutingRequest] = {

    val pickupSpaceTime = SpaceTime((request.pickUpLocation, request.departAt.atTime))
//    val customerAgentBody =
//      StreetVehicle(request.customer.vehicleId, pickupSpaceTime, WALK, asDriver = true)
    val rideHailVehicleAtOrigin = StreetVehicle(
      rideHailLocation.vehicleId,
      SpaceTime((rideHailLocation.currentLocation.loc, request.departAt.atTime)),
      CAR,
      asDriver = false
    )
    val rideHailVehicleAtPickup =
      StreetVehicle(rideHailLocation.vehicleId, pickupSpaceTime, CAR, asDriver = false)

// route from ride hailing vehicle to customer
    val rideHailAgent2Customer = RoutingRequest(
      rideHailLocation.currentLocation.loc,
      request.pickUpLocation,
      request.departAt,
      Vector(),
      Vector(rideHailVehicleAtOrigin)
    )
// route from customer to destination
    val rideHail2Destination = RoutingRequest(
      request.pickUpLocation,
      request.destination,
      request.departAt,
      Vector(),
      Vector(rideHailVehicleAtPickup)
    )

    List(rideHailAgent2Customer, rideHail2Destination)
  }

  def requestRoutes(routingRequests: List[RoutingRequest]): Unit = {
    val preservedOrder = routingRequests.map(_.requestId)
    val theFutures = Future
      .sequence(routingRequests.map { rRequest =>
        akka.pattern.ask(router, rRequest).mapTo[RoutingResponse]
      })
      .foreach { responseList =>
        val requestIdToResponse = responseList.map { response =>
          response.requestId.get -> response
        }.toMap
        val orderedResponses = preservedOrder.map(requestId => requestIdToResponse(requestId))
        self ! RoutingResponses(orderedResponses)
      }
  }

  def printRepositionDistanceSum(
    repositionVehicles: Vector[(Id[Vehicle], Location)]
  ): Unit = {
    var sumOfDistances: Double = 0
    var numberOfTrips = 0
    for (repositionVehicle <- repositionVehicles) {
      val (vehicleId, destinationLocation) = repositionVehicle
      val rideHailAgentLocation = getIdleVehicles(vehicleId)

      sumOfDistances += beamServices.geo
        .distInMeters(rideHailAgentLocation.currentLocation.loc, destinationLocation)
      numberOfTrips += 1
    }

//println(s"sumOfDistances: $sumOfDistances - numberOfTrips: $numberOfTrips")

    DebugLib.emptyFunctionForSettingBreakPoint()
  }

  def getIdleVehicles: mutable.HashMap[Id[Vehicle], RideHailAgentLocation] = {
    availableRideHailVehicles
  }

  private def getServiceStatusOf(vehicleId: Id[Vehicle]): RideHailServiceStatus = {
    if (availableRideHailVehicles.contains(vehicleId)) {
      Available
    } else if (inServiceRideHailVehicles.contains(vehicleId)) {
      InService
    } else if (outOfServiceRideHailVehicles.contains(vehicleId)) {
      OutOfService
    } else {
      log.error(s"Vehicle {} does not have a service status, assuming out of service", vehicleId)
      OutOfService
    }
  }

  private def updateLocationOfAgent(
    vehicleId: Id[Vehicle],
    whenWhere: SpaceTime,
    serviceStatus: RideHailServiceStatus
  ) = {
    serviceStatus match {
      case Available =>
        availableRideHailVehicles.get(vehicleId) match {
          case Some(prevLocation) =>
            val newLocation = prevLocation.copy(currentLocation = whenWhere)
            availableRideHailAgentSpatialIndex.remove(
              prevLocation.currentLocation.loc.getX,
              prevLocation.currentLocation.loc.getY,
              prevLocation
            )
            availableRideHailAgentSpatialIndex.put(
              newLocation.currentLocation.loc.getX,
              newLocation.currentLocation.loc.getY,
              newLocation
            )
            availableRideHailVehicles.put(newLocation.vehicleId, newLocation)
          case None =>
        }
      case InService =>
        inServiceRideHailVehicles.get(vehicleId) match {
          case Some(prevLocation) =>
            val newLocation = prevLocation.copy(currentLocation = whenWhere)
            inServiceRideHailAgentSpatialIndex.remove(
              prevLocation.currentLocation.loc.getX,
              prevLocation.currentLocation.loc.getY,
              prevLocation
            )
            inServiceRideHailAgentSpatialIndex.put(
              newLocation.currentLocation.loc.getX,
              newLocation.currentLocation.loc.getY,
              newLocation
            )
            inServiceRideHailVehicles.put(newLocation.vehicleId, newLocation)
          case None =>
        }
      case OutOfService =>
        outOfServiceRideHailVehicles.get(vehicleId) match {
          case Some(prevLocation) =>
            val newLocation = prevLocation.copy(currentLocation = whenWhere)
            outOfServiceRideHailAgentSpatialIndex.remove(
              prevLocation.currentLocation.loc.getX,
              prevLocation.currentLocation.loc.getY,
              prevLocation
            )
            outOfServiceRideHailAgentSpatialIndex.put(
              newLocation.currentLocation.loc.getX,
              newLocation.currentLocation.loc.getY,
              newLocation
            )
            outOfServiceRideHailVehicles.put(newLocation.vehicleId, newLocation)
          case None =>
        }
    }
  }

  private def makeAvailable(agentLocation: RideHailAgentLocation) = {
    availableRideHailVehicles.put(agentLocation.vehicleId, agentLocation)
    availableRideHailAgentSpatialIndex.put(
      agentLocation.currentLocation.loc.getX,
      agentLocation.currentLocation.loc.getY,
      agentLocation
    )
    inServiceRideHailVehicles.remove(agentLocation.vehicleId)
    inServiceRideHailAgentSpatialIndex.remove(
      agentLocation.currentLocation.loc.getX,
      agentLocation.currentLocation.loc.getY,
      agentLocation
    )
    outOfServiceRideHailVehicles.remove(agentLocation.vehicleId)
    outOfServiceRideHailAgentSpatialIndex.remove(
      agentLocation.currentLocation.loc.getX,
      agentLocation.currentLocation.loc.getY,
      agentLocation
    )
  }

  private def putIntoService(agentLocation: RideHailAgentLocation) = {
    availableRideHailVehicles.remove(agentLocation.vehicleId)
    availableRideHailAgentSpatialIndex.remove(
      agentLocation.currentLocation.loc.getX,
      agentLocation.currentLocation.loc.getY,
      agentLocation
    )
    outOfServiceRideHailVehicles.remove(agentLocation.vehicleId)
    outOfServiceRideHailAgentSpatialIndex.remove(
      agentLocation.currentLocation.loc.getX,
      agentLocation.currentLocation.loc.getY,
      agentLocation
    )
    inServiceRideHailVehicles.put(agentLocation.vehicleId, agentLocation)
    inServiceRideHailAgentSpatialIndex.put(
      agentLocation.currentLocation.loc.getX,
      agentLocation.currentLocation.loc.getY,
      agentLocation
    )
  }

  private def putOutOfService(agentLocation: RideHailAgentLocation) = {
    availableRideHailVehicles.remove(agentLocation.vehicleId)
    availableRideHailAgentSpatialIndex.remove(
      agentLocation.currentLocation.loc.getX,
      agentLocation.currentLocation.loc.getY,
      agentLocation
    )
    inServiceRideHailVehicles.remove(agentLocation.vehicleId)
    inServiceRideHailAgentSpatialIndex.remove(
      agentLocation.currentLocation.loc.getX,
      agentLocation.currentLocation.loc.getY,
      agentLocation
    )
    outOfServiceRideHailVehicles.put(agentLocation.vehicleId, agentLocation)
    outOfServiceRideHailAgentSpatialIndex.put(
      agentLocation.currentLocation.loc.getX,
      agentLocation.currentLocation.loc.getY,
      agentLocation
    )
  }

  private def handleReservation(request: RideHailRequest, travelProposal: TravelProposal): Unit = {

    surgePricingManager.addRideCost(
      request.departAt.atTime,
      travelProposal.estimatedPrice.doubleValue(),
      request.pickUpLocation
    )

    // Create driver's passenger schedule
    val passengerSchedule = PassengerSchedule()
      .addLegs(travelProposal.responseRideHail2Pickup.itineraries.head.toBeamTrip.legs) // Adds empty trip to customer
      .addPassenger(
        request.customer,
        List(travelProposal.responseRideHail2Dest.itineraries.head.legs.head.beamLeg)
      ) // Adds customer's actual trip to destination
    putIntoService(travelProposal.rideHailAgentLocation)
    lockVehicle(travelProposal.rideHailAgentLocation.vehicleId)

    // Create confirmation info but stash until we receive ModifyPassengerScheduleAck
    pendingModifyPassengerScheduleAcks.put(
      request.requestId.toString,
      RideHailResponse(request, Some(travelProposal))
    )

    log.debug(
      "Reserving vehicle: {} customer: {} request: {}",
      travelProposal.rideHailAgentLocation.vehicleId,
      request.customer.personId,
      request.requestId
    )

    modifyPassengerScheduleManager.reserveVehicle(
      passengerSchedule,
      travelProposal.rideHailAgentLocation,
      Some(request.requestId)
    )
  }

  private def completeReservation(
    requestId: Int,
    triggersToSchedule: Seq[ScheduleTrigger]
  ): Unit = {
    pendingModifyPassengerScheduleAcks.remove(requestId.toString) match {
      case Some(response) =>
        log.debug("Completing reservation for {}", requestId)
        unlockVehicle(response.travelProposal.get.rideHailAgentLocation.vehicleId)

        log.debug(
          "completing reservation - customer: {} - vehicle: {}",
          response.request.customer.personId,
          response.travelProposal.get.rideHailAgentLocation.vehicleId
        )

        response.request.customer.personRef.get ! response.copy(
          triggersToSchedule = triggersToSchedule.toVector
        )
        rideHailResourceAllocationManager.reservationCompletionNotice(
          response.request.customer.personId,
          response.travelProposal.get.rideHailAgentLocation.vehicleId
        )
      case None =>
        log.error("Vehicle was reserved by another agent for inquiry id {}", requestId)
        sender() ! RideHailResponse.dummyWithError(RideHailVehicleTakenError)
    }
  }

  private def handleReservationRequest(request: RideHailRequest): Unit = {
// We always use the request buffer, but depending on whether we process this
// request immediately or on timeout we take different paths
    rideHailResourceAllocationManager.addRequestToBuffer(request)

    if (processBufferedRequestsOnTimeout) {
      request.customer.personRef.get ! DelayedRideHailResponse
    } else {
      findAllocationsAndProcess(request.departAt.atTime)
    }

  }

  /*
   * This is common code for both use cases, batch processing and processing a single reservation request immediately.
   * The differences are resolved through the boolean processBufferedRequestsOnTimeout.
   */
  private def findAllocationsAndProcess(tick: Int) = {
    var allRoutesRequired: List[RoutingRequest] = List()

    rideHailResourceAllocationManager.allocateVehiclesToCustomers(tick) match {
      case VehicleAllocations(allocations) =>
        allocations.foreach { allocation =>
          allocation match {
            case RoutingRequiredToAllocateVehicle(request, routesRequired) =>
              // Client has requested routes
              reservationIdToRequest.put(request.requestId, request)
              routesRequired.foreach(
                rReq => routeRequestIdToRideHailRequestId.put(rReq.staticRequestId, request.requestId)
              )
              allRoutesRequired = allRoutesRequired ++ routesRequired
            case alloc @ VehicleMatchedToCustomers(request, rideHailAgentLocation, pickDropIdWithRoutes) =>
              val triggersToSchedule = createTriggersFromMatchedVehicles(alloc)
              request.customer.personRef.get ! RideHailResponse(request, None, None, triggersToSchedule)
              rideHailResourceAllocationManager.removeRequestFromBuffer(request)
            case NoVehicleAllocated(request) =>
              val theResponse = RideHailResponse(request, None, Some(DriverNotFoundError))
              if (processBufferedRequestsOnTimeout) {
                allTriggersToScheduleForBufferedReservations = allTriggersToScheduleForBufferedReservations :+ ScheduleTrigger(
                  RideHailResponseTrigger(tick, theResponse),
                  request.customer.personRef.get
                )
              } else {
                request.customer.personRef.get ! theResponse
              }
              rideHailResourceAllocationManager.removeRequestFromBuffer(request)
          }
        }
      case _ =>
    }
    if (!allRoutesRequired.isEmpty) {
      requestRoutes(allRoutesRequired)
    } else if (processBufferedRequestsOnTimeout) {
      scheduleNextBufferedTrigger(allTriggersToScheduleForBufferedReservations)
      allTriggersToScheduleForBufferedReservations = Vector()
    }
  }

  def createTriggersFromMatchedVehicles(alloc: VehicleMatchedToCustomers): Vector[ScheduleTrigger] = {
    //TODO actual trigger creation here
//      Option(travelProposalCache.getIfPresent(request.requestId.toString)) match {
//        case Some(travelProposal) =>
//          if (inServiceRideHailVehicles.contains(travelProposal.rideHailAgentLocation.vehicleId) ||
//            lockedVehicles.contains(travelProposal.rideHailAgentLocation.vehicleId) || outOfServiceRideHailVehicles
//            .contains(travelProposal.rideHailAgentLocation.vehicleId)) {
//            findDriverAndSendRoutingRequests(request)
//          } else {
//            handleReservation(request, travelProposal)
//          }
//        case None =>
//          findDriverAndSendRoutingRequests(request)
//      }
    Vector()
  }

}
