package beam.agentsim.agents.ridehail

import java.util
import java.util.concurrent.TimeUnit

import akka.actor.{ActorLogging, ActorRef, Props, Stash}
import akka.event.LoggingReceive
import akka.pattern._
import akka.util.Timeout
import beam.agentsim
import beam.agentsim.Resource._
import beam.agentsim.ResourceManager.{NotifyVehicleResourceIdle, VehicleManager}
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.modalbehaviors.DrivesVehicle._
import beam.agentsim.agents.ridehail.RideHailAgent._
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
import beam.agentsim.scheduler.BeamAgentScheduler.ScheduleTrigger
import beam.agentsim.scheduler.Trigger
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.router.BeamRouter.{Location, RoutingRequest, RoutingResponse, _}
import beam.router.Modes.BeamMode._
import beam.router.model.{BeamLeg, EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.sim.{BeamServices, HasServices}
import beam.utils.DebugLib
import com.eaio.uuid.UUIDGen
import com.google.common.cache.{Cache, CacheBuilder}
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.collections.QuadTree
import org.matsim.core.utils.geometry.CoordUtils
import org.matsim.vehicles.Vehicle

import scala.collection.JavaConverters._
import scala.collection.immutable.HashSet
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object RideHailAgentLocationWithRadiusOrdering extends Ordering[(RideHailAgentLocation, Double)] {
  override def compare(
    o1: (RideHailAgentLocation, Double),
    o2: (RideHailAgentLocation, Double)
  ): Int = {
    java.lang.Double.compare(o1._2, o2._2)
  }
}

object RideHailManager {

  val INITIAL_RIDE_HAIL_LOCATION_HOME = "HOME"
  val INITIAL_RIDE_HAIL_LOCATION_UNIFORM_RANDOM = "UNIFORM_RANDOM"
  val INITIAL_RIDE_HAIL_LOCATION_ALL_AT_CENTER = "ALL_AT_CENTER"
  val INITIAL_RIDE_HAIL_LOCATION_ALL_IN_CORNER = "ALL_IN_CORNER"

  def nextRideHailInquiryId: Id[RideHailRequest] = {
    Id.create(UUIDGen.createTime(UUIDGen.newTime()).toString, classOf[RideHailRequest])
  }

  def props(
    services: BeamServices,
    scheduler: ActorRef,
    router: ActorRef,
    parkingManager: ActorRef,
    boundingBox: Envelope,
    surgePricingManager: RideHailSurgePricingManager,
    tncIterationStats: Option[TNCIterationStats]
  ): Props = {
    Props(
      new RideHailManager(
        services,
        scheduler,
        router,
        parkingManager,
        boundingBox,
        surgePricingManager,
        tncIterationStats
      )
    )
  }

  sealed trait RideHailServiceStatus

  case class NotifyIterationEnds()

  case class TravelProposal(
    rideHailAgentLocation: RideHailAgentLocation,
    passengerSchedule: PassengerSchedule,
    estimatedPrice: Map[Id[Person], Double],
    poolingInfo: Option[PoolingInfo] = None
  ) {

    def timeToCustomer(passenger: VehiclePersonId) =
      passengerSchedule.legsBeforePassengerBoards(passenger).map(_.duration).sum

    def travelTimeForCustomer(passenger: VehiclePersonId) =
      passengerSchedule.legsWithPassenger(passenger).map(_.duration).sum

    def toEmbodiedBeamLegsForCustomer(passenger: VehiclePersonId): Vector[EmbodiedBeamLeg] = {
      passengerSchedule
        .legsWithPassenger(passenger)
        .map { beamLeg =>
          EmbodiedBeamLeg(
            beamLeg,
            rideHailAgentLocation.vehicleId,
            false,
            estimatedPrice(passenger.personId),
            false,
            passengerSchedule.schedule.values.flatMap(_.riders).size > 1
          )
        }
        .toVector
    }
    override def toString: String =
      s"RHA: ${rideHailAgentLocation.vehicleId}, price: $estimatedPrice, passengerSchedule: $passengerSchedule"
  }

  case class RoutingResponses(
    tick: Int,
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
    currentLocationUTM: SpaceTime
  ) {

    def toStreetVehicle: StreetVehicle = {
      StreetVehicle(vehicleId, currentLocationUTM, CAR, asDriver = true)
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

  case class ContinueBufferedRideHailRequests(tick: Int)

  /* Available means vehicle can be assigned to a new customer */
  case object Available extends RideHailServiceStatus

  case object InService extends RideHailServiceStatus

  case object OutOfService extends RideHailServiceStatus

}

class RideHailManager(
  val beamServices: BeamServices,
  val scheduler: ActorRef,
  val router: ActorRef,
  val parkingManager: ActorRef,
  val boundingBox: Envelope,
  val surgePricingManager: RideHailSurgePricingManager,
  val tncIterationStats: Option[TNCIterationStats]
) extends VehicleManager
    with ActorLogging
    with HasServices
    with Stash {

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

  private val rideHailResourceAllocationManager = RideHailResourceAllocationManager(
    beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.name,
    this
  )

  val modifyPassengerScheduleManager =
    new RideHailModifyPassengerScheduleManager(
      log,
      self,
      this,
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
  private val pendingModifyPassengerScheduleAcks = mutable.HashMap[Int, RideHailResponse]()
  private var numPendingRoutingRequestsForReservations = 0
  private val parkingInquiryCache = collection.mutable.HashMap[Int, RideHailAgentLocation]()
  private val pendingAgentsSentToPark = collection.mutable.Map[Id[Vehicle], ParkingStall]()

  // Tracking Inquiries and Reservation Requests
  val inquiryIdToInquiryAndResponse: mutable.Map[Int, (RideHailRequest, SingleOccupantQuoteAndPoolingInfo)] =
    mutable.Map()
  val routeRequestIdToRideHailRequestId: mutable.Map[Int, Int] = mutable.Map()
  val reservationIdToRequest: mutable.Map[Int, RideHailRequest] = mutable.Map()

  // Are we in the middle of processing a batch?
  var currentlyProcessingTimeoutTrigger: Option[TriggerWithId] = None

  //context.actorSelection("user/")
  //rideHailIterationHistoryActor send message to ridheailiterationhsitoryactor

  DebugLib.emptyFunctionForSettingBreakPoint()

  override def receive: Receive = LoggingReceive {
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
      val whenWhere = whenWhereOpt.getOrElse(getRideHailAgentLocation(vehicleId).currentLocationUTM)

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

    case inquiry @ RideHailRequest(RideHailInquiry, _, _, _, _, _, _, _) =>
      handleRideHailInquiry(inquiry)

    case R5Network(network) =>
      rideHailNetworkApi.setR5Network(network)

    /*
     * In the following case, we are calculating routes in batch for the allocation manager,
     * so we add these to the allocation buffer and then resume the allocation process.
     */
    case RoutingResponses(tick, responses)
        if reservationIdToRequest.contains(routeRequestIdToRideHailRequestId(responses.head.staticRequestId)) =>
      numPendingRoutingRequestsForReservations = numPendingRoutingRequestsForReservations - responses.size
      responses.foreach { routeResponse =>
        val request = reservationIdToRequest(routeRequestIdToRideHailRequestId(routeResponse.staticRequestId))
        rideHailResourceAllocationManager.addRouteForRequestToBuffer(request, routeResponse)
      }
      self ! ContinueBufferedRideHailRequests(tick)

    /*
     * Routing Responses from a Ride Hail Inquiry
     * In this case we can treat the responses as if they apply to a single request
     * for a single occupant trip.
     */
    case RoutingResponses(_, responses)
        if inquiryIdToInquiryAndResponse.contains(routeRequestIdToRideHailRequestId(responses.head.staticRequestId)) =>
      val (request, singleOccupantQuoteAndPoolingInfo) = inquiryIdToInquiryAndResponse(
        routeRequestIdToRideHailRequestId(responses.head.staticRequestId)
      )

      // If any response contains no RIDE_HAIL legs, then the router failed
      if (responses.map(_.itineraries.filter(_.tripClassifier.equals(RIDE_HAIL)).isEmpty).contains(true)) {
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
      } else {
        // We can rely on preserved ordering here (see RideHailManager.requestRoutes),
        // for a simple single-occupant trip sequence, we know that first
        // itin is RH2Customer and second is Pickup2Destination.
        val embodiedBeamTrip: EmbodiedBeamTrip = EmbodiedBeamTrip(
          responses
            .map { response =>
              response.itineraries.filter(p => p.tripClassifier.equals(RIDE_HAIL)).headOption
            }
            .flatten
            .flatMap(_.legs)
            .toIndexedSeq
        )
        val driverPassengerSchedule = singleOccupantItinsToPassengerSchedule(request, embodiedBeamTrip)

        val baseFare = embodiedBeamTrip.legs.map(_.cost).sum

        val travelProposal = TravelProposal(
          singleOccupantQuoteAndPoolingInfo.rideHailAgentLocation,
          driverPassengerSchedule,
          calcFare(request, driverPassengerSchedule, baseFare),
          singleOccupantQuoteAndPoolingInfo.poolingInfo
        )
        travelProposalCache.put(request.requestId.toString, travelProposal)

        request.customer.personRef.get ! RideHailResponse(request, Some(travelProposal))
      }
      inquiryIdToInquiryAndResponse.remove(request.requestId)
      responses.foreach(rResp => routeRequestIdToRideHailRequestId.remove(rResp.staticRequestId))

    case reserveRide @ RideHailRequest(ReserveRide, _, _, _, _, _, _, _) =>
      handleReservationRequest(reserveRide)

    case modifyPassengerScheduleAck @ ModifyPassengerScheduleAck(
          requestIdOpt,
          triggersToSchedule,
          vehicleId,
          tick
        ) =>
      pendingAgentsSentToPark.get(vehicleId) match {
        case Some(_) =>
          log.debug(
            "modifyPassengerScheduleAck received, handling with outOfServiceManager {}",
            modifyPassengerScheduleAck
          )
          outOfServiceVehicleManager.releaseTrigger(vehicleId, triggersToSchedule)
        case None =>
          requestIdOpt match {
            case None =>
              // None here means this is part of repositioning, i.e. not tied to a reservation request
              log.debug(
                "modifyPassengerScheduleAck received, handling with modifyPassengerScheduleManager {}",
                modifyPassengerScheduleAck
              )
              modifyPassengerScheduleManager
                .modifyPassengerScheduleAckReceived(
                  triggersToSchedule,
                  tick
                )
            case Some(requestId) =>
              // Some here means this is part of a reservation / dispatch of vehicle to a customer
              log.debug("modifyPassengerScheduleAck received, completing reservation {}", modifyPassengerScheduleAck)
              completeReservation(requestId, tick, triggersToSchedule)
          }
      }

    case UpdateTravelTimeLocal(travelTime) =>
      rideHailNetworkApi.setTravelTime(travelTime)

    case DebugRideHailManagerDuringExecution =>
      modifyPassengerScheduleManager.printState()

    case trigger @ TriggerWithId(BufferedRideHailRequestsTrigger(tick), triggerId) =>
      currentlyProcessingTimeoutTrigger match {
        case Some(_) =>
          stash()
        case None =>
          currentlyProcessingTimeoutTrigger = Some(trigger)
          log.debug("Starting wave of buffered at {}", tick)
          modifyPassengerScheduleManager.startWaveOfRepositioningOrBatchedReservationRequests(tick, triggerId)
          findAllocationsAndProcess(tick)
      }

    case ContinueBufferedRideHailRequests(tick) =>
      if (tick == modifyPassengerScheduleManager.getCurrentTick.getOrElse(tick))
        findAllocationsAndProcess(tick)

    case trigger @ TriggerWithId(RideHailRepositioningTrigger(tick), triggerId) =>
//      DebugRepositioning.produceRepositioningDebugImages(tick, this)
      currentlyProcessingTimeoutTrigger match {
        case Some(_) =>
          stash()
        case None =>
          currentlyProcessingTimeoutTrigger = Some(trigger)
          startRepositioning(tick, triggerId)
      }

    case ReduceAwaitingRepositioningAckMessagesByOne =>
      modifyPassengerScheduleManager.cancelRepositionAttempt()

    case MoveOutOfServiceVehicleToDepotParking(passengerSchedule, tick, vehicleId, stall) =>
      pendingAgentsSentToPark.put(vehicleId, stall)
      outOfServiceVehicleManager.initiateMovementToParkingDepot(vehicleId, passengerSchedule, tick)

    case RepositionVehicleRequest(passengerSchedule, tick, vehicleId, rideHailAgent) =>
      if (getIdleVehicles.contains(vehicleId)) {
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
        outOfServiceVehicleManager.handleInterruptReply(vehicleId, tick)
      } else {
        modifyPassengerScheduleManager.handleInterruptReply(reply)
      }

    case reply @ InterruptedWhileDriving(interruptId, vehicleId, tick, interruptedPassengerSchedule, _) =>
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
        originUTM = agentLocation.currentLocationUTM.loc,
        destinationUTM = stall.locationUTM,
        departureTime = agentLocation.currentLocationUTM.time,
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

  def singleOccupantItinsToPassengerSchedule(
    request: RideHailRequest,
    embodiedTrip: EmbodiedBeamTrip
  ): PassengerSchedule = {
    val beamLegs = BeamLeg.makeLegsConsistent(embodiedTrip.toBeamTrip.legs.toList)
    PassengerSchedule()
      .addLegs(beamLegs)
      .addPassenger(request.customer, beamLegs.tail)
  }

  def calcFare(
    request: RideHailRequest,
    trip: PassengerSchedule,
    baseFare: Double
  ): Map[Id[Person], Double] = {
    val farePerSecond = DefaultCostPerSecond * surgePricingManager
      .getSurgeLevel(
        request.pickUpLocationUTM,
        request.departAt
      )
    val fare = (trip.legsWithPassenger(request.customer).map(_.duration).sum.toDouble * farePerSecond) + baseFare

    Map(request.customer.personId -> fare)
  }

  def findRefuelStationAndSendVehicle(rideHailAgentLocation: RideHailAgentLocation): Unit = {
    val inquiry = DepotParkingInquiry(
      rideHailAgentLocation.vehicleId,
      rideHailAgentLocation.currentLocationUTM.loc,
      ParkingStall.RideHailManager
    )
    parkingInquiryCache.put(inquiry.requestId, rideHailAgentLocation)
    parkingManager ! inquiry
  }

  def handleRideHailInquiry(inquiry: RideHailRequest): Unit = {
    rideHailResourceAllocationManager.respondToInquiry(inquiry) match {
      case NoVehiclesAvailable =>
        log.debug("{} -- NoVehiclesAvailable", inquiry.requestId)
        inquiry.customer.personRef.get ! RideHailResponse(inquiry, None, Some(DriverNotFoundError))
      case inquiryResponse @ SingleOccupantQuoteAndPoolingInfo(agentLocation, poolingInfo) =>
        inquiryIdToInquiryAndResponse.put(inquiry.requestId, (inquiry, inquiryResponse))
        val routingRequests = createRoutingRequestsToCustomerAndDestination(inquiry.departAt, inquiry, agentLocation)
        routingRequests.foreach(rReq => routeRequestIdToRideHailRequestId.put(rReq.staticRequestId, inquiry.requestId))
        requestRoutes(inquiry.departAt, routingRequests)
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
    excludeRideHailVehicles: Set[Id[Vehicle]] = Set(),
    secondsPerEuclideanMeterFactor: Double = 0.1 // (~13.4m/s)^-1 * 1.4
  ): Vector[RideHailAgentETA] = {
    var start = System.currentTimeMillis()
    val nearbyAvailableRideHailAgents = availableRideHailAgentSpatialIndex
      .getDisk(pickupLocation.getX, pickupLocation.getY, radius)
      .asScala
      .filter(x => availableRideHailVehicles.contains(x.vehicleId) && !excludeRideHailVehicles.contains(x.vehicleId))
    var end = System.currentTimeMillis()
    val diff1 = end - start

    start = System.currentTimeMillis()
    val times2RideHailAgents = nearbyAvailableRideHailAgents
      .map { rideHailAgentLocation =>
        val distance =
          CoordUtils.calcProjectedEuclideanDistance(pickupLocation, rideHailAgentLocation.currentLocationUTM.loc)
        // we consider the time to travel to the customer and the time before the vehicle is actually ready (due to
        // already moving or dropping off a customer, etc.)
        val extra = Math.max(rideHailAgentLocation.currentLocationUTM.time - customerRequestTime, 0)
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
          .calcProjectedEuclideanDistance(pickupLocation, rideHailAgentLocation.currentLocationUTM.loc)
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

  def getVehicleState(vehicleId: Id[Vehicle]): BeamVehicleState =
    vehicleState(vehicleId)

  def createRoutingRequestsToCustomerAndDestination(
    requestTime: Int,
    request: RideHailRequest,
    rideHailLocation: RideHailAgentLocation
  ): List[RoutingRequest] = {

    val pickupSpaceTime = SpaceTime((request.pickUpLocationUTM, request.departAt))
//    val customerAgentBody =
//      StreetVehicle(request.customer.vehicleId, pickupSpaceTime, WALK, asDriver = true)
    val rideHailVehicleAtOrigin = StreetVehicle(
      rideHailLocation.vehicleId,
      SpaceTime((rideHailLocation.currentLocationUTM.loc, requestTime)),
      CAR,
      asDriver = false
    )
    val rideHailVehicleAtPickup =
      StreetVehicle(rideHailLocation.vehicleId, pickupSpaceTime, CAR, asDriver = false)

// route from ride hailing vehicle to customer
    val rideHailAgent2Customer = RoutingRequest(
      rideHailLocation.currentLocationUTM.loc,
      request.pickUpLocationUTM,
      requestTime,
      Vector(),
      Vector(rideHailVehicleAtOrigin)
    )
// route from customer to destination
    val rideHail2Destination = RoutingRequest(
      request.pickUpLocationUTM,
      request.destinationUTM,
      requestTime,
      Vector(),
      Vector(rideHailVehicleAtPickup)
    )

    List(rideHailAgent2Customer, rideHail2Destination)
  }

  def requestRoutes(tick: Int, routingRequests: List[RoutingRequest]): Unit = {
    val preservedOrder = routingRequests.map(_.staticRequestId)
    val theFutures = Future
      .sequence(routingRequests.map { rRequest =>
        akka.pattern.ask(router, rRequest).mapTo[RoutingResponse]
      })
      .foreach { responseList =>
        val requestIdToResponse = responseList.map { response =>
          response.staticRequestId -> response
        }.toMap
        val orderedResponses = preservedOrder.map(requestId => requestIdToResponse(requestId))
        self ! RoutingResponses(tick, orderedResponses)
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
        .distUTMInMeters(rideHailAgentLocation.currentLocationUTM.loc, destinationLocation)
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
            val newLocation = prevLocation.copy(currentLocationUTM = whenWhere)
            availableRideHailAgentSpatialIndex.remove(
              prevLocation.currentLocationUTM.loc.getX,
              prevLocation.currentLocationUTM.loc.getY,
              prevLocation
            )
            availableRideHailAgentSpatialIndex.put(
              newLocation.currentLocationUTM.loc.getX,
              newLocation.currentLocationUTM.loc.getY,
              newLocation
            )
            availableRideHailVehicles.put(newLocation.vehicleId, newLocation)
          case None =>
        }
      case InService =>
        inServiceRideHailVehicles.get(vehicleId) match {
          case Some(prevLocation) =>
            val newLocation = prevLocation.copy(currentLocationUTM = whenWhere)
            inServiceRideHailAgentSpatialIndex.remove(
              prevLocation.currentLocationUTM.loc.getX,
              prevLocation.currentLocationUTM.loc.getY,
              prevLocation
            )
            inServiceRideHailAgentSpatialIndex.put(
              newLocation.currentLocationUTM.loc.getX,
              newLocation.currentLocationUTM.loc.getY,
              newLocation
            )
            inServiceRideHailVehicles.put(newLocation.vehicleId, newLocation)
          case None =>
        }
      case OutOfService =>
        outOfServiceRideHailVehicles.get(vehicleId) match {
          case Some(prevLocation) =>
            val newLocation = prevLocation.copy(currentLocationUTM = whenWhere)
            outOfServiceRideHailAgentSpatialIndex.remove(
              prevLocation.currentLocationUTM.loc.getX,
              prevLocation.currentLocationUTM.loc.getY,
              prevLocation
            )
            outOfServiceRideHailAgentSpatialIndex.put(
              newLocation.currentLocationUTM.loc.getX,
              newLocation.currentLocationUTM.loc.getY,
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
      agentLocation.currentLocationUTM.loc.getX,
      agentLocation.currentLocationUTM.loc.getY,
      agentLocation
    )
    inServiceRideHailVehicles.remove(agentLocation.vehicleId)
    inServiceRideHailAgentSpatialIndex.remove(
      agentLocation.currentLocationUTM.loc.getX,
      agentLocation.currentLocationUTM.loc.getY,
      agentLocation
    )
    outOfServiceRideHailVehicles.remove(agentLocation.vehicleId)
    outOfServiceRideHailAgentSpatialIndex.remove(
      agentLocation.currentLocationUTM.loc.getX,
      agentLocation.currentLocationUTM.loc.getY,
      agentLocation
    )
  }

  private def putIntoService(agentLocation: RideHailAgentLocation) = {
    availableRideHailVehicles.remove(agentLocation.vehicleId)
    availableRideHailAgentSpatialIndex.remove(
      agentLocation.currentLocationUTM.loc.getX,
      agentLocation.currentLocationUTM.loc.getY,
      agentLocation
    )
    outOfServiceRideHailVehicles.remove(agentLocation.vehicleId)
    outOfServiceRideHailAgentSpatialIndex.remove(
      agentLocation.currentLocationUTM.loc.getX,
      agentLocation.currentLocationUTM.loc.getY,
      agentLocation
    )
    inServiceRideHailVehicles.put(agentLocation.vehicleId, agentLocation)
    inServiceRideHailAgentSpatialIndex.put(
      agentLocation.currentLocationUTM.loc.getX,
      agentLocation.currentLocationUTM.loc.getY,
      agentLocation
    )
  }

  private def putOutOfService(agentLocation: RideHailAgentLocation) = {
    availableRideHailVehicles.remove(agentLocation.vehicleId)
    availableRideHailAgentSpatialIndex.remove(
      agentLocation.currentLocationUTM.loc.getX,
      agentLocation.currentLocationUTM.loc.getY,
      agentLocation
    )
    inServiceRideHailVehicles.remove(agentLocation.vehicleId)
    inServiceRideHailAgentSpatialIndex.remove(
      agentLocation.currentLocationUTM.loc.getX,
      agentLocation.currentLocationUTM.loc.getY,
      agentLocation
    )
    outOfServiceRideHailVehicles.put(agentLocation.vehicleId, agentLocation)
    outOfServiceRideHailAgentSpatialIndex.put(
      agentLocation.currentLocationUTM.loc.getX,
      agentLocation.currentLocationUTM.loc.getY,
      agentLocation
    )
  }

  private def handleReservation(request: RideHailRequest, tick: Int, travelProposal: TravelProposal): Unit = {
    surgePricingManager.addRideCost(
      request.departAt,
      travelProposal.estimatedPrice(request.customer.personId),
      request.pickUpLocationUTM
    )

    // This makes the vehicle unavailable for others to reserve
    putIntoService(travelProposal.rideHailAgentLocation)

    // Create confirmation info but stash until we receive ModifyPassengerScheduleAck
    pendingModifyPassengerScheduleAcks.put(
      request.requestId,
      RideHailResponse(request, Some(travelProposal))
    )

    log.debug(
      "Reserving vehicle: {} customer: {} request: {} pendingAcks: {}",
      travelProposal.rideHailAgentLocation.vehicleId,
      request.customer.personId,
      request.requestId,
      s"(${pendingModifyPassengerScheduleAcks.size}) ${pendingModifyPassengerScheduleAcks.keySet.map(_.toString).mkString(",")}"
    )

    modifyPassengerScheduleManager.reserveVehicle(
      travelProposal.passengerSchedule,
      travelProposal.rideHailAgentLocation,
      tick,
      Some(request.requestId)
    )
  }

  private def completeReservation(
    requestId: Int,
    tick: Int,
    finalTriggersToSchedule: Vector[ScheduleTrigger]
  ): Unit = {
    log.debug(
      "Removing request: {} pendingAcks: {} pendingRoutes: {} requestBufferEmpty: {}",
      requestId,
      s"(${pendingModifyPassengerScheduleAcks.size}) ${pendingModifyPassengerScheduleAcks.keySet.map(_.toString).mkString(",")}",
      numPendingRoutingRequestsForReservations,
      rideHailResourceAllocationManager.isBufferEmpty
    )
    pendingModifyPassengerScheduleAcks.remove(requestId) match {
      case Some(response) =>
        val theVehicle = response.travelProposal.get.rideHailAgentLocation.vehicleId
        log.debug(
          "Completing reservation {} for customer {} and vehicle {}",
          requestId,
          response.request.customer.personId,
          theVehicle
        )

        if (processBufferedRequestsOnTimeout) {
          modifyPassengerScheduleManager.addTriggersToSendWithCompletion(finalTriggersToSchedule)
          response.request.customer.personRef.get ! response.copy(triggersToSchedule = Vector())
          response.request.groupedWithOtherRequests.foreach { subReq =>
            subReq.customer.personRef.get ! response.copy(triggersToSchedule = Vector())
          }
        } else {
          response.request.customer.personRef.get ! response.copy(
            triggersToSchedule = finalTriggersToSchedule
          )
        }
        // The following is an API call to allow implementing class to process or cleanup
        rideHailResourceAllocationManager.reservationCompletionNotice(response.request.customer.personId, theVehicle)
      case None =>
        log.error("Vehicle was reserved by another agent for inquiry id {}", requestId)
        sender() ! RideHailResponse.dummyWithError(RideHailVehicleTakenError)
    }
    if (processBufferedRequestsOnTimeout) {
      self ! ContinueBufferedRideHailRequests(tick)
    }
  }

  private def handleReservationRequest(request: RideHailRequest): Unit = {
    // We always use the request buffer, but depending on whether we process this
    // request immediately or on timeout we take different paths
    rideHailResourceAllocationManager.addRequestToBuffer(request)

    if (processBufferedRequestsOnTimeout) {
      request.customer.personRef.get ! DelayedRideHailResponse
    } else {
      findAllocationsAndProcess(request.departAt)
    }

  }

  /*
   * This is common code for both use cases, batch processing and processing a single reservation request immediately.
   * The differences are resolved through the boolean processBufferedRequestsOnTimeout.
   */
  private def findAllocationsAndProcess(tick: Int) = {
    var allRoutesRequired: List[RoutingRequest] = List()
    log.debug("findAllocationsAndProcess @ {}", tick)

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
            case alloc @ VehicleMatchedToCustomers(request, rideHailAgentLocation, pickDropIdWithRoutes)
                if !pickDropIdWithRoutes.isEmpty =>
              handleReservation(request, tick, createTravelProposal(alloc))
              rideHailResourceAllocationManager.removeRequestFromBuffer(request)
            case VehicleMatchedToCustomers(request, _, _) =>
              failedAllocation(request, tick)
            case NoVehicleAllocated(request) =>
              failedAllocation(request, tick)
          }
        }
      case _ =>
    }
    if (!allRoutesRequired.isEmpty) {
      log.debug("requesting {} routes at {}", allRoutesRequired.size, tick)
      numPendingRoutingRequestsForReservations = numPendingRoutingRequestsForReservations + allRoutesRequired.size
      requestRoutes(tick, allRoutesRequired)
    } else if (processBufferedRequestsOnTimeout && pendingModifyPassengerScheduleAcks.isEmpty &&
               rideHailResourceAllocationManager.isBufferEmpty && numPendingRoutingRequestsForReservations == 0 &&
               currentlyProcessingTimeoutTrigger.isDefined) {
      log.debug("sendCompletionAndScheduleNewTimeout from 1156")
      modifyPassengerScheduleManager.sendCompletionAndScheduleNewTimeout(BatchedReservation, tick)
      cleanUp
    }
  }

  def createTravelProposal(alloc: VehicleMatchedToCustomers): TravelProposal = {
    val passSched = pickDropsToPassengerSchedule(alloc.pickDropIdWithRoutes)
    TravelProposal(
      alloc.rideHailAgentLocation,
      passSched,
      calcFare(alloc.request, passSched, 0),
      None
    )
  }

  def pickDropsToPassengerSchedule(pickDrops: List[PickDropIdAndLeg]): PassengerSchedule = {
    val consistentPickDrops =
      pickDrops.map(_.personId).zip(BeamLeg.makeLegsConsistent(pickDrops.map(_.leg.get.beamLeg)))
    val allLegs = consistentPickDrops.map(_._2)
    var passSched = PassengerSchedule().addLegs(allLegs)
    consistentPickDrops.groupBy(_._1).foreach { personPickDrop =>
      val firstLeg = personPickDrop._2.head._2
      val lastLeg = personPickDrop._2.last._2
      val subtrip = allLegs.dropWhile(_ != firstLeg).drop(1).takeWhile(_ != lastLeg) :+ lastLeg
      passSched = passSched.addPassenger(personPickDrop._1, subtrip)
    }
    passSched
  }

  def failedAllocation(request: RideHailRequest, tick: Int): Unit = {
    val theResponse = RideHailResponse(request, None, Some(DriverNotFoundError))
    if (processBufferedRequestsOnTimeout) {
      modifyPassengerScheduleManager.addTriggerToSendWithCompletion(
        ScheduleTrigger(
          RideHailResponseTrigger(tick, theResponse),
          request.customer.personRef.get
        )
      )
    } else {
      request.customer.personRef.get ! theResponse
    }
    rideHailResourceAllocationManager.removeRequestFromBuffer(request)
  }

  def cleanUp = {
    currentlyProcessingTimeoutTrigger = None
    unstashAll()
  }

  def startRepositioning(tick: Int, triggerId: Long) = {
    log.debug("Starting wave of repositioning at {}", tick)
    modifyPassengerScheduleManager.startWaveOfRepositioningOrBatchedReservationRequests(tick, triggerId)

    val repositionVehicles: Vector[(Id[Vehicle], Location)] =
      rideHailResourceAllocationManager.repositionVehicles(tick)

    if (repositionVehicles.isEmpty) {
      log.debug("sendCompletionAndScheduleNewTimeout from 1204")
      modifyPassengerScheduleManager.sendCompletionAndScheduleNewTimeout(Reposition, tick)
      cleanUp
    } else {
      modifyPassengerScheduleManager.setNumberOfRepositioningsToProcess(repositionVehicles.size)
    }

    for ((vehicleId, destinationLocation) <- repositionVehicles) {
      if (getIdleVehicles.contains(vehicleId)) {
        val rideHailAgentLocation = getIdleVehicles(vehicleId)

        val rideHailVehicleAtOrigin = StreetVehicle(
          rideHailAgentLocation.vehicleId,
          SpaceTime((rideHailAgentLocation.currentLocationUTM.loc, tick)),
          CAR,
          asDriver = false
        )
        val routingRequest = RoutingRequest(
          originUTM = rideHailAgentLocation.currentLocationUTM.loc,
          destinationUTM = destinationLocation,
          departureTime = tick,
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
        modifyPassengerScheduleManager.cancelRepositionAttempt()
      }
    }

  }

}
