package beam.agentsim.agents.ridehail

import java.awt.Color
import java.util
import java.util.concurrent.TimeUnit

import akka.actor.{ActorLogging, ActorRef, Cancellable, Props}
import akka.event.Logging
import akka.pattern._
import akka.util.Timeout
import beam.agentsim
import beam.agentsim.Resource._
import beam.agentsim.ResourceManager.{NotifyVehicleResourceIdle, VehicleManager}
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.modalbehaviors.DrivesVehicle._
import beam.agentsim.agents.ridehail.{MoveOutOfServiceVehicleToDepotParking, OutOfServiceVehicleManager}
import beam.agentsim.agents.ridehail.RideHailManager._
import beam.agentsim.agents.ridehail.RideHailAgent._
import beam.agentsim.agents.ridehail.RideHailIterationHistoryActor.GetCurrentIterationRideHailStats
import beam.agentsim.agents.ridehail.allocation._
import beam.agentsim.agents.vehicles.AccessErrorCodes.{CouldNotFindRouteToCustomer, DriverNotFoundError, RideHailVehicleTakenError}
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
import beam.router.RoutingModel
import beam.router.RoutingModel.{BeamTime, DiscreteTime}
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
import scala.collection.{concurrent, mutable}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

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

  val INITIAL_RIDEHAIL_LOCATION_HOME = "HOME"
  val INITIAL_RIDEHAIL_LOCATION_UNIFORM_RANDOM = "UNIFORM_RANDOM"
  val INITIAL_RIDEHAIL_LOCATION_ALL_AT_CENTER = "ALL_AT_CENTER"
  val INITIAL_RIDEHAIL_LOCATION_ALL_IN_CORNER = "ALL_IN_CORNER"

  def nextRideHailInquiryId: Id[RideHailRequest] = {
    Id.create(UUIDGen.createTime(UUIDGen.newTime()).toString, classOf[RideHailRequest])
  }

  val dummyRideHailVehicleId = Id.createVehicleId("dummyRideHailVehicle")

  case class NotifyIterationEnds()

  case class TravelProposal(
    rideHailAgentLocation: RideHailAgentLocation,
    timeToCustomer: Long,
    estimatedPrice: BigDecimal,
    estimatedTravelTime: Option[Duration],
    responseRideHail2Pickup: RoutingResponse,
    responseRideHail2Dest: RoutingResponse
  ) {
    override def toString(): String =
      s"RHA: ${rideHailAgentLocation.vehicleId}, waitTime: $timeToCustomer, price: $estimatedPrice, travelTime: $estimatedTravelTime"
  }

  case class RoutingResponses(
    request: RideHailRequest,
    rideHailLocation: Option[RideHailAgentLocation],
    routingResponses: List[RoutingResponse]
  )

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

    def toStreetVehicle(): StreetVehicle = {
      StreetVehicle(vehicleId, currentLocation, CAR, true)
    }
  }
  case class RideHailAgentETA(
    agentLocation: RideHailAgentLocation,
    distance: Double,
    timeToCustomer: Double
  )

  case object RideUnavailableAck

  case object RideAvailableAck

  case object DebugRideHailManagerDuringExecution

  case class RepositionResponse(
    rnd1: RideHailAgentLocation,
    rnd2: RideHailManager.RideHailAgentLocation,
    rnd1Response: RoutingResponse,
    rnd2Response: RoutingResponse
  )

  case class BufferedRideHailRequestsTimeout(tick: Int) extends Trigger

  case class RideHailAllocationManagerTimeout(tick: Int) extends Trigger

  sealed trait RideHailServiceStatus
  /* Available means vehicle can be assigned to a new customer */
  case object Available extends RideHailServiceStatus
  case object InService extends RideHailServiceStatus
  case object OutOfService extends RideHailServiceStatus

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
    with HasServices {

  implicit val timeout: Timeout = Timeout(50000, TimeUnit.SECONDS)

  val radiusInMeters: Double =
    beamServices.beamConfig.beam.agentsim.agents.rideHail.rideHailManager.radiusInMeters
  override val resources: mutable.Map[Id[BeamVehicle], BeamVehicle] =
    mutable.Map[Id[BeamVehicle], BeamVehicle]()

  private val vehicleState: mutable.Map[Id[Vehicle], BeamVehicleState] =
    mutable.Map[Id[Vehicle], BeamVehicleState]()

  private val DefaultCostPerMinute = BigDecimal(
    beamServices.beamConfig.beam.agentsim.agents.rideHail.defaultCostPerMinute
  )
  private val DefaultCostPerSecond = DefaultCostPerMinute / 60.0d

  private val rideHailAllocationManagerTimeoutInSeconds = {
    beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.timeoutInSeconds
  }

  private val pendingDummyRideHailRequests: mutable.Map[Int, RideHailRequest] =
    mutable.Map[Int, RideHailRequest]()

  private val completedDummyRideHailRequests: mutable.Map[Int, RideHailRequest] =
    mutable.Map[Int, RideHailRequest]()

  val allocationManager: String =
    beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.name

  val rideHailNetworkApi: RideHailNetworkAPI = new RideHailNetworkAPI()

  val tncIterationStats: Option[TNCIterationStats] = {
    val rideHailIterationHistoryActor =
      context.actorSelection("/user/rideHailIterationHistoryActor")
    val future =
      rideHailIterationHistoryActor.ask(GetCurrentIterationRideHailStats)
    Await
      .result(future, timeout.duration)
      .asInstanceOf[Option[TNCIterationStats]]
  }
  tncIterationStats.foreach(_.logMap())

  val rideHailResourceAllocationManager = RideHailResourceAllocationManager(
    beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.name,
    this
  )

  val modifyPassengerScheduleManager =
    new RideHailModifyPassengerScheduleManager(
      log,
      self,
      rideHailAllocationManagerTimeoutInSeconds,
      scheduler,
      beamServices.beamConfig
    )

  val outOfServiceVehicleManager =
    new OutOfServiceVehicleManager(
      log,
      self,
      this
    )

  private val handleRideHailInquirySubmitted = mutable.Set[String]()

  var nextCompleteNoticeRideHailAllocationTimeout: CompletionNotice = _

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
  private val availableRideHailVehicles =
    concurrent.TrieMap[Id[Vehicle], RideHailAgentLocation]()
  private val outOfServiceRideHailVehicles =
    concurrent.TrieMap[Id[Vehicle], RideHailAgentLocation]()
  private val inServiceRideHailVehicles =
    concurrent.TrieMap[Id[Vehicle], RideHailAgentLocation]()

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
  private val pendingModifyPassengerScheduleAcks =
    collection.concurrent.TrieMap[String, RideHailResponse]()
  private var lockedVehicles = Set[Id[Vehicle]]()
  private val parkingInquiryCache = collection.concurrent.TrieMap[Int, RideHailAgentLocation]()
  private val pendingAgentsSentToPark = collection.mutable.Map[Id[Vehicle], ParkingStall]()

  //context.actorSelection("user/")
  //rideHailIterationHistoryActor send message to ridheailiterationhsitoryactor

  DebugLib.emptyFunctionForSettingBreakPoint()


  var timeSpendForHandleReservationRequestMs: Long = 0
  var nHandleReservationRequest: Int = 0

  var timeSpendForFindDriverAndSendRoutingRequests: Long = 0
  var nFindDriverAndSendRoutingRequests: Int = 0

  var shouldTurnOnDebugLevel: Boolean = false

  val maybeTick: Option[Cancellable] = if (true) {
    Some(context.system.scheduler.schedule(2.seconds, 30.seconds, self, "tick")(context.dispatcher))
  } else None

  override def postStop: Unit = {
    maybeTick.foreach(_.cancel())
    super.postStop()
  }

  override def receive: Receive = {
    case "tick" =>
      log.info(s"timeSpendForHandleReservationRequestMs: ${timeSpendForHandleReservationRequestMs}, nHandleReservationRequest: ${nHandleReservationRequest}, AVG: ${timeSpendForHandleReservationRequestMs.toDouble / nHandleReservationRequest}")
      timeSpendForHandleReservationRequestMs = 0
      nHandleReservationRequest = 0
      log.info(s"timeSpendForFindDriverAndSendRoutingRequests: ${timeSpendForFindDriverAndSendRoutingRequests}, nFindDriverAndSendRoutingRequests: ${nFindDriverAndSendRoutingRequests}, AVG: ${timeSpendForFindDriverAndSendRoutingRequests.toDouble / nFindDriverAndSendRoutingRequests}")
      timeSpendForFindDriverAndSendRoutingRequests = 0
      nFindDriverAndSendRoutingRequests = 0
      if (shouldTurnOnDebugLevel) {
        context.system.eventStream.setLogLevel(Logging.DebugLevel)
      }

    case ev @ StopDrivingIfNoPassengerOnBoardReply(success, requestId, tick) =>
      Option(travelProposalCache.getIfPresent(requestId.toString)) match {
        case Some(travelProposal) =>
          if (success) {
            travelProposal.rideHailAgentLocation.rideHailAgent ! StopDriving(tick)
            travelProposal.rideHailAgentLocation.rideHailAgent ! Resume()
          }
          rideHailResourceAllocationManager.handleRideCancellationReply(ev)

        case None =>
          log.error(s"request not found: $ev")
      }

    case NotifyIterationEnds() =>
      surgePricingManager.incrementIteration()
      sender ! Unit // return empty object to blocking caller

    case RegisterResource(vehId: Id[Vehicle]) =>
      resources.put(agentsim.vehicleId2BeamVehicleId(vehId), beamServices.vehicles(vehId))

    case ev @ NotifyVehicleResourceIdle(
          vehicleId: Id[Vehicle],
          whenWhereOpt,
          passengerSchedule,
          beamVehicleState,
          triggerId
        ) =>
      log.debug("RHM.NotifyVehicleResourceIdle: {}", ev)

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

    case NotifyResourceInUse(vehId: Id[Vehicle], whenWhere) =>
      updateLocationOfAgent(vehId, whenWhere, InService)

    case BeamVehicleStateUpdate(id, beamVehicleState) =>
      vehicleState.put(id, beamVehicleState)

    case MATSimNetwork(network) =>
      rideHailNetworkApi.setMATSimNetwork(network)

    case CheckInResource(vehicleId: Id[Vehicle], whenWhere) =>
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

    case inquiry @ RideHailRequest(RideHailInquiry, _, _, _, _) =>
      findDriverAndSendRoutingRequests(inquiry, caller = "RideHailRequest")

    case R5Network(network) =>
      rideHailNetworkApi.setR5Network(network)

    // In the following case, we have responses but no RHAgent defined, which means we are calculating routes
    // for the allocation manager, so we resume the allocation process.
    case RoutingResponses(request, None, responses) =>
      //      println(s"got routingResponse: ${request.requestId} with no RHA")
      findDriverAndSendRoutingRequests(request, responses, caller = "RoutingResponses")

    case RoutingResponses(
        request,
        Some(rideHailLocation),
        responses
        ) =>
      //      println(s"got routingResponse: ${request.requestId} with RHA ${rideHailLocation.vehicleId}")
      val itins = responses.map { response =>
        response.itineraries.filter(_.tripClassifier.equals(RIDE_HAIL))
      }

      // We can rely on preserved ordering here (see RideHailManager.requestRoutes),
      // for a simple single-occupant trip sequence, we know that first
      // itin is RH2Customer and second is Pickup2Destination.
      // TODO generalize the processing below to allow for pooling
      val itins2Cust = itins.head
      val itins2Dest = itins.last

      val rideHailFarePerSecond = DefaultCostPerSecond * surgePricingManager
        .getSurgeLevel(
          request.pickUpLocation,
          request.departAt.atTime.toDouble
        )
      val customerPlans2Costs: Map[RoutingModel.EmbodiedBeamTrip, BigDecimal] =
        itins2Dest.map(trip => (trip, rideHailFarePerSecond * trip.totalTravelTimeInSecs)).toMap

      if (itins2Cust.nonEmpty && itins2Dest.nonEmpty) {
        val (customerTripPlan, cost) = customerPlans2Costs.minBy(_._2)

        if (customerTripPlan.tripClassifier == WALK || customerTripPlan.legs.size < 2) {
          request.customer.personRef.get ! RideHailResponse(
            request,
            None,
            Some(CouldNotFindRouteToCustomer)
          )
        } else {

          val tripDriver2Cust = RoutingResponse(
            Vector(
              itins2Cust.head.copy(legs = itins2Cust.head.legs.map(l => l.copy(asDriver = true)))
            ),
            java.util.UUID.randomUUID()
          )
          val timeToCustomer =
            tripDriver2Cust.itineraries.head.totalTravelTimeInSecs

          val tripCust2Dest = RoutingResponse(
            Vector(
              customerTripPlan.copy(
                legs = customerTripPlan.legs.zipWithIndex.map(
                  legWithInd =>
                    legWithInd._1.copy(
                      asDriver = legWithInd._1.beamLeg.mode == WALK,
                      unbecomeDriverOnCompletion = legWithInd._2 == 2,
                      beamLeg = legWithInd._1.beamLeg.updateStartTime(legWithInd._1.beamLeg.startTime + timeToCustomer),
                      cost =
                        if (legWithInd._1.beamLeg == customerTripPlan
                              .legs(1)
                              .beamLeg) {
                          cost
                        } else {
                          0.0
                        }
                  )
                )
              )
            ),
            java.util.UUID.randomUUID()
          )

          val travelProposal = TravelProposal(
            rideHailLocation,
            timeToCustomer,
            cost,
            Some(FiniteDuration(customerTripPlan.totalTravelTimeInSecs, TimeUnit.SECONDS)),
            tripDriver2Cust,
            tripCust2Dest
          )

          travelProposalCache.put(request.requestId.toString, travelProposal)

          //        log.debug(s"Found ridehail ${rideHailLocation.vehicleId} for person=${request.customer.personId} and ${request.requestType} " +
          //          s"requestId=${request.requestId}, timeToCustomer=$timeToCustomer seconds and cost=$$$cost")

          request.requestType match {
            case RideHailInquiry =>
              request.customer.personRef.get ! RideHailResponse(request, Some(travelProposal))
            case ReserveRide =>
              self ! request
          }
        }
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

    case reserveRide @ RideHailRequest(ReserveRide, _, _, _, _) =>
      val s = System.currentTimeMillis()
      handleReservationRequest(reserveRide)
      val diff = System.currentTimeMillis() - s
      timeSpendForHandleReservationRequestMs += diff
      nHandleReservationRequest += 1

    case modifyPassengerScheduleAck @ ModifyPassengerScheduleAck(
          requestIdOpt,
          triggersToSchedule,
          vehicleId
        ) =>
      if (pendingAgentsSentToPark.contains(vehicleId)) {
        log.debug(
          "modifyPassengerScheduleAck received, handling with outOfServiceManager: " + modifyPassengerScheduleAck
        )
        outOfServiceVehicleManager.releaseTrigger(vehicleId, triggersToSchedule)
      } else {

        requestIdOpt match {
          case None =>
            log.debug(
              "modifyPassengerScheduleAck received, handling with modifyPassengerScheduleManager: " + modifyPassengerScheduleAck
            )
            modifyPassengerScheduleManager
              .modifyPassengerScheduleAckReceivedForRepositioning(
                triggersToSchedule
              )
          case Some(requestId) =>
            log.debug("modifyPassengerScheduleAck received, completing reservation: " + modifyPassengerScheduleAck)
            completeReservation(requestId, triggersToSchedule)
        }
      }

    case UpdateTravelTime(travelTime) =>
      rideHailNetworkApi.setTravelTime(travelTime)

    case DebugRideHailManagerDuringExecution =>
      modifyPassengerScheduleManager.printState()

    case TriggerWithId(BufferedRideHailRequestsTimeout(tick), triggerId) =>
      rideHailResourceAllocationManager.updateVehicleAllocations(tick, triggerId, this)

    case TriggerWithId(RideHailAllocationManagerTimeout(tick), triggerId) =>
      val produceDebugImages = true
      if (produceDebugImages) {
        if (tick > 0 && tick.toInt % 3600 == 0 && tick < 24 * 3600) {
          val spatialPlot = new SpatialPlot(1100, 1100, 50)

          for (veh <- resources.values) {
            spatialPlot.addPoint(
              PointToPlot(getRideHailAgentLocation(veh.id).currentLocation.loc, Color.BLACK, 5)
            )
          }

          tncIterationStats.foreach(tncIterationStats => {

            val tazEntries = tncIterationStats getCoordinatesWithRideHailStatsEntry (tick, tick + 3600)

            for (tazEntry <- tazEntries.filter(x => x._2.sumOfRequestedRides > 0)) {
              spatialPlot.addPoint(
                PointToPlot(
                  tazEntry._1,
                  Color.RED,
                  10 + Math.log(tazEntry._2.sumOfRequestedRides).toInt
                )
              )
            }
          })

          val iteration = "it." + beamServices.iterationNumber
          spatialPlot.writeImage(
            beamServices.matsimServices.getControlerIO
              .getIterationFilename(
                beamServices.iterationNumber,
                tick.toInt / 3600 + "locationOfAgentsInitally.png"
              )
              .replace(iteration, iteration + "/rideHailDebugging")
          )
        }
      }

      modifyPassengerScheduleManager.startWaiveOfRepositioningRequests(tick, triggerId)

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

      for (repositionVehicle <- repositionVehicles) {

        val (vehicleId, destinationLocation) = repositionVehicle

        if (getIdleVehicles.contains(vehicleId)) {
          val rideHailAgentLocation = getIdleVehicles(vehicleId)

          val rideHailAgent = rideHailAgentLocation.rideHailAgent

          val rideHailVehicleAtOrigin = StreetVehicle(
            rideHailAgentLocation.vehicleId,
            SpaceTime((rideHailAgentLocation.currentLocation.loc, tick)),
            CAR,
            asDriver = false
          )

          val departAt = DiscreteTime(tick.toInt)

          val routingRequest = RoutingRequest(
            origin = rideHailAgentLocation.currentLocation.loc,
            destination = destinationLocation,
            departureTime = departAt,
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
              val modRHA2Cust: IndexedSeq[RoutingModel.EmbodiedBeamTrip] =
                itins2Cust.map(l => l.copy(legs = l.legs.map(c => c.copy(asDriver = true)))).toIndexedSeq
              val rideHailAgent2CustomerResponseMod = RoutingResponse(modRHA2Cust, routingRequest.staticRequestId)

              // TODO: extract creation of route to separate method?
              val passengerSchedule = PassengerSchedule().addLegs(
                rideHailAgent2CustomerResponseMod.itineraries.head.toBeamTrip.legs
              )

              self ! RepositionVehicleRequest(passengerSchedule, tick, vehicleId, rideHailAgent)

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
        modifyPassengerScheduleManager
          .modifyPassengerScheduleAckReceivedForRepositioning(Vector())
      }

    case InterruptedWhileIdle(interruptId, vehicleId, tick) =>
      if (pendingAgentsSentToPark.contains(vehicleId)) {
        outOfServiceVehicleManager.handleInterrupt(vehicleId)
      } else {
        modifyPassengerScheduleManager.handleInterrupt(
          InterruptedWhileIdle.getClass,
          interruptId,
          None,
          vehicleId,
          tick
        )
      }

    case InterruptedAt(interruptId, interruptedPassengerSchedule, _, vehicleId, tick) =>
      if (pendingAgentsSentToPark.contains(vehicleId)) {
        log.error(
          "It is not expected in the current implementation that a moving vehicle would be stopped and sent for charging"
        )
      } else {
        modifyPassengerScheduleManager.handleInterrupt(
          InterruptedAt.getClass,
          interruptId,
          Some(interruptedPassengerSchedule),
          vehicleId,
          tick
        )
      }

    case DepotParkingInquiryResponse(None, requestId) =>
      val vehId = parkingInquiryCache.get(requestId).get.vehicleId
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
        departureTime = DiscreteTime(agentLocation.currentLocation.time.toInt),
        transitModes = Vector(),
        streetVehicles = Vector(agentLocation.toStreetVehicle())
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
      log.warning(s"unknown message received by RideHailManager $msg")

  }

  def findRefuelStationAndSendVehicle(rideHailAgentLocation: RideHailAgentLocation) = {
    val inquiry = DepotParkingInquiry(
      rideHailAgentLocation.vehicleId,
      rideHailAgentLocation.currentLocation.loc,
      ParkingStall.RideHailManager
    )
    parkingInquiryCache.put(inquiry.requestId, rideHailAgentLocation)
    parkingManager ! inquiry
  }

  private def printRepositionDistanceSum(
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

  // Returns Boolean indicating success/failure
  def findDriverAndSendRoutingRequests(
    request: RideHailRequest,
    responses: List[RoutingResponse] = List(),
    caller: String
  ): Unit = {
    val s = System.currentTimeMillis()
    log.debug(
      "Finding driver at tick {}, available: {}, inService: {}, outOfService: {}",
      request.departAt,
      availableRideHailVehicles.size,
      inServiceRideHailVehicles.size,
      outOfServiceRideHailVehicles.size
    )
    log.debug(
      "Finding driver at tick {}, caller: {}",
      request.departAt, caller
    )

    val vehicleAllocationRequest = VehicleAllocationRequest(request, responses)

    val vehicleAllocationResponse =
      rideHailResourceAllocationManager.proposeVehicleAllocation(vehicleAllocationRequest)

    vehicleAllocationResponse match {
      case VehicleAllocation(agentLocation, None) =>
        log.debug(s"${request.requestId} -- VehicleAllocation(${agentLocation.vehicleId}, None)")
        requestRoutes(
          request,
          Some(agentLocation),
          createRoutingRequestsToCustomerAndDestination(request, agentLocation)
        )
      case VehicleAllocation(agentLocation, Some(routingResponses)) =>
        log.debug(s"${request.requestId} -- VehicleAllocation(agentLocation, Some())")
        self ! RoutingResponses(request, Some(agentLocation), routingResponses)
      case RoutingRequiredToAllocateVehicle(_, routesRequired) =>
        log.debug(s"${request.requestId} -- RoutingRequired")
        requestRoutes(request, None, routesRequired)
      case NoVehicleAllocated =>
        log.debug(s"${request.requestId} -- NoVehicleAllocated")
        request.customer.personRef.get ! RideHailResponse(request, None, Some(DriverNotFoundError))
    }
    val diff = System.currentTimeMillis - s
    timeSpendForFindDriverAndSendRoutingRequests += diff
    nFindDriverAndSendRoutingRequests += 1
  }

  def getRideHailAgentLocation(vehicleId: Id[Vehicle]): RideHailAgentLocation = {
    getServiceStatusOf(vehicleId) match {
      case Available =>
        availableRideHailVehicles.get(vehicleId).get
      case InService =>
        inServiceRideHailVehicles.get(vehicleId).get
      case OutOfService =>
        outOfServiceRideHailVehicles.get(vehicleId).get
    }
  }

  private def getServiceStatusOf(vehicleId: Id[Vehicle]): RideHailServiceStatus = {
    if (availableRideHailVehicles.contains(vehicleId)) {
      Available
    } else if (inServiceRideHailVehicles.contains(vehicleId)) {
      InService
    } else if (outOfServiceRideHailVehicles.contains(vehicleId)) {
      OutOfService
    } else {
      log.error(s"Vehicle ${vehicleId} does not have a service status, assuming out of service")
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

    // Modify RH agent passenger schedule and create BeamAgentScheduler message that will dispatch RH agent to do the
    // pickup
    val passengerSchedule = PassengerSchedule()
      .addLegs(travelProposal.responseRideHail2Pickup.itineraries.head.toBeamTrip.legs) // Adds empty trip to customer
      .addPassenger(
        request.customer,
        travelProposal.responseRideHail2Dest.itineraries.head.legs
          .filter(_.isRideHail)
          .map(_.beamLeg)
      ) // Adds customer's actual trip to destination
    putIntoService(travelProposal.rideHailAgentLocation)
    lockVehicle(travelProposal.rideHailAgentLocation.vehicleId)

    // Create confirmation info but stash until we receive ModifyPassengerScheduleAck
    //val tripLegs = travelProposal.responseRideHail2Dest.itineraries.head.legs.map(_.beamLeg)
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
      passengerSchedule.schedule.head._1.startTime,
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
          s"completing reservation - customer: ${response.request.customer.personId} " +
          s"- vehicle: ${response.travelProposal.get.rideHailAgentLocation.vehicleId}"
        )

        val bufferedRideHailRequests = rideHailResourceAllocationManager.bufferedRideHailRequests

        if (bufferedRideHailRequests.isReplacementVehicle(
              response.travelProposal.get.rideHailAgentLocation.vehicleId
            )) {

          bufferedRideHailRequests.addTriggerMessages(triggersToSchedule.toVector)

          bufferedRideHailRequests.replacementVehicleReservationCompleted(
            response.travelProposal.get.rideHailAgentLocation.vehicleId
          )

          bufferedRideHailRequests.tryClosingBufferedRideHailRequestWaive()
        } else {

          response.request.customer.personRef.get ! response.copy(
            triggersToSchedule = triggersToSchedule.toVector
          )
        }
        rideHailResourceAllocationManager.reservationCompletionNotice(
          response.request.customer.personId,
          response.travelProposal.get.rideHailAgentLocation.vehicleId
        )
      case None =>
        log.error(s"Vehicle was reserved by another agent for inquiry id $requestId")
        sender() ! RideHailResponse.dummyWithError(RideHailVehicleTakenError)
    }
  }

  def getClosestIdleVehiclesWithinRadiusByETA(
    pickupLocation: Coord,
    radius: Double,
    customerRequestTime: Long,
    secondsPerEuclideanMeterFactor: Double = 0.1 // (~13.4m/s)^-1 * 1.4
  ): Vector[RideHailAgentETA] = {
    val nearbyRideHailAgents = availableRideHailAgentSpatialIndex
      .getDisk(pickupLocation.getX, pickupLocation.getY, radius)
      .asScala
      .toVector
    val times2RideHailAgents =
      nearbyRideHailAgents.map(rideHailAgentLocation => {
        val distance = CoordUtils
          .calcProjectedEuclideanDistance(pickupLocation, rideHailAgentLocation.currentLocation.loc)
        // we consider the time to travel to the customer and the time before the vehicle is actually ready (due to
        // already moving or dropping off a customer, etc.)
        val timeToCustomer = distance * secondsPerEuclideanMeterFactor + Math
          .max(rideHailAgentLocation.currentLocation.time - customerRequestTime, 0)
        RideHailAgentETA(rideHailAgentLocation, distance, timeToCustomer)
      })
    times2RideHailAgents
      .filter(x => availableRideHailVehicles.contains(x.agentLocation.vehicleId))
      .sortBy(_.timeToCustomer)
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

  private def handleReservationRequest(request: RideHailRequest): Unit = {
    //    log.debug(s"handleReservationRequest: $request")
    Option(travelProposalCache.getIfPresent(request.requestId.toString)) match {
      case Some(travelProposal) =>
        if (inServiceRideHailVehicles.contains(travelProposal.rideHailAgentLocation.vehicleId) ||
            lockedVehicles.contains(travelProposal.rideHailAgentLocation.vehicleId) || outOfServiceRideHailVehicles
              .contains(travelProposal.rideHailAgentLocation.vehicleId)) {
          findDriverAndSendRoutingRequests(request, caller = "handleReservationRequest")
        } else {
          if (pendingDummyRideHailRequests.contains(request.requestId)) {

            println(s"stranding customer: ${request.customer.personId}")

            request.customer.personRef.get ! RideHailResponse(request, Some(travelProposal))

            completedDummyRequest(request)
          } else {
            handleReservation(request, travelProposal)
          }

        }
      case None =>
        findDriverAndSendRoutingRequests(request, caller = "handleReservationRequest")
    }
  }

  def attemptToCancelCurrentRideRequest(tick: Int, requestId: Int): Unit = {
    Option(travelProposalCache.getIfPresent(requestId.toString)) match {
      case Some(travelProposal) =>
        log.debug(
          s"trying to stop vehicle: ${travelProposal.rideHailAgentLocation.vehicleId}, tick: $tick"
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

  def getIdleVehicles: collection.concurrent.TrieMap[Id[Vehicle], RideHailAgentLocation] = {
    availableRideHailVehicles
  }

  def cleanCurrentPickupAssignment(request: RideHailRequest) = {
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

  def assignDummyRidehail(request: RideHailRequest): Unit = {
    pendingDummyRideHailRequests.put(request.requestId, request)
    DebugLib.emptyFunctionForSettingBreakPoint()
  }

  def getPendingDummyRequests: mutable.Map[Int, RideHailRequest] = {
    pendingDummyRideHailRequests
  }

  def completedDummyRequest(request: RideHailRequest): Unit = {
    pendingDummyRideHailRequests.remove(request.requestId)
    completedDummyRideHailRequests.put(request.requestId, request)
  }

  def getCompletedDummyRequests: mutable.Map[Int, RideHailRequest] = {
    completedDummyRideHailRequests
  }

  def removeDummyRequest(request: RideHailRequest): Unit = {
    completedDummyRideHailRequests.remove(request.requestId)
  }

  def createRoutingRequestsToCustomerAndDestination(
    request: RideHailRequest,
    rideHailLocation: RideHailAgentLocation
  ): List[RoutingRequest] = {

    val pickupSpaceTime = SpaceTime((request.pickUpLocation, request.departAt.atTime))
    val customerAgentBody =
      StreetVehicle(request.customer.vehicleId, pickupSpaceTime, WALK, asDriver = true)
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
      Vector(customerAgentBody, rideHailVehicleAtPickup)
    )

    List(rideHailAgent2Customer, rideHail2Destination)
  }

  def requestRoutesToCustomerAndDestination(
    request: RideHailRequest,
    rideHailLocation: RideHailAgentLocation
  ): List[RoutingRequest] = {

    val pickupSpaceTime = SpaceTime((request.pickUpLocation, request.departAt.atTime))
    val customerAgentBody =
      StreetVehicle(request.customer.vehicleId, pickupSpaceTime, WALK, asDriver = true)
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
      Vector(customerAgentBody, rideHailVehicleAtPickup)
    )

    List(rideHailAgent2Customer, rideHail2Destination)
  }

  def requestRoutes(
    rideHailRequest: RideHailRequest,
    rideHailAgentLocation: Option[RideHailAgentLocation],
    routingRequests: List[RoutingRequest]
  ) = {
    val preservedOrder = routingRequests.map(_.requestId)
    Future
      .sequence(routingRequests.map { req =>
        akka.pattern.ask(router, req).mapTo[RoutingResponse]
      })
      .foreach { responseList =>
        val requestIdToResponse = responseList.map { response =>
          (response.requestId.get -> response)
        }.toMap
        val orderedResponses = preservedOrder.map(requestId => requestIdToResponse(requestId))
        self ! RoutingResponses(rideHailRequest, rideHailAgentLocation, orderedResponses)
      }
  }

}
