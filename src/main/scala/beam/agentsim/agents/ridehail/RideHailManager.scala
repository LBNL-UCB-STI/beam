package beam.agentsim.agents.ridehail

import akka.actor.SupervisorStrategy.Stop
import akka.actor.{ActorLogging, ActorRef, BeamLoggingReceive, Cancellable, OneForOneStrategy, Props, Stash, Terminated}
import akka.pattern.pipe
import akka.util.Timeout
import beam.agentsim.Resource._
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.choice.mode.DrivingCost
import beam.agentsim.agents.household.CAVSchedule.RouteOrEmbodyRequest
import beam.agentsim.agents.modalbehaviors.DrivesVehicle._
import beam.agentsim.agents.ridehail.RideHailAgent._
import beam.agentsim.agents.ridehail.RideHailManager._
import beam.agentsim.agents.ridehail.RideHailManagerHelper.{Available, Refueling, RideHailAgentLocation}
import beam.agentsim.agents.ridehail.allocation.{DispatchProductType, _}
import beam.agentsim.agents.ridehail.kpis.RealTimeKpis
import beam.agentsim.agents.vehicles.AccessErrorCodes.{
  CouldNotFindRouteToCustomer,
  DriverNotFoundError,
  RideHailVehicleTakenError
}
import beam.agentsim.agents.vehicles.BeamVehicle.BeamVehicleState
import beam.agentsim.agents.vehicles.FuelType.Electricity
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.agents.vehicles.{PassengerSchedule, VehicleManager, _}
import beam.agentsim.agents.{Dropoff, InitializeTrigger, MobilityRequest, Pickup}
import beam.agentsim.events.{RideHailFleetStateEvent, SpaceTime}
import beam.agentsim.infrastructure.{ParkingInquiryResponse, ParkingStall}
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.agentsim.scheduler.{HasTriggerId, Trigger}
import beam.router.BeamRouter.{Location, RoutingRequest, RoutingResponse, _}
import beam.router.Modes.BeamMode._
import beam.router.model.{BeamLeg, EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.router.osm.TollCalculator
import beam.router.skim.TAZSkimsCollector.TAZSkimsCollectionTrigger
import beam.router.skim.event.TAZSkimmerEvent
import beam.router.{BeamRouter, RouteHistory}
import beam.sim.RideHailFleetInitializer.RideHailAgentInitializer
import beam.sim._
import beam.sim.config.BeamConfig.Beam.Debug
import beam.sim.metrics.SimulationMetricCollector._
import beam.utils._
import beam.utils.logging.pattern.ask
import beam.utils.logging.{LogActorState, LoggingMessageActor}
import beam.utils.matsim_conversion.ShapeUtils.QuadTreeBounds
import beam.utils.reflection.ReflectionUtils
import com.conveyal.r5.transit.TransportNetwork
import com.google.common.cache.{Cache, CacheBuilder}
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.population.{Activity, Person}
import org.matsim.api.core.v01.{Coord, Id, Scenario}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.controler.OutputDirectoryHierarchy
import org.matsim.core.utils.misc.Time

import java.awt.Color
import java.io.File
import java.util
import java.util.concurrent.TimeUnit
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Random

object RideHailManager {
  val INITIAL_RIDE_HAIL_LOCATION_HOME = "HOME"
  val INITIAL_RIDE_HAIL_LOCATION_RANDOM_ACTIVITY = "RANDOM_ACTIVITY"
  val INITIAL_RIDE_HAIL_LOCATION_UNIFORM_RANDOM = "UNIFORM_RANDOM"
  val INITIAL_RIDE_HAIL_LOCATION_ALL_AT_CENTER = "ALL_AT_CENTER"
  val INITIAL_RIDE_HAIL_LOCATION_ALL_IN_CORNER = "ALL_IN_CORNER"

  type VehicleId = Id[BeamVehicle]

  case object NotifyIterationEnds

  case class RecoverFromStuckness(tick: Int, triggerId: Long) extends HasTriggerId

  case class TravelProposal(
    rideHailAgentLocation: RideHailAgentLocation,
    passengerSchedule: PassengerSchedule,
    estimatedPrice: Map[Id[Person], Double],
    poolingInfo: Option[PoolingInfo] = None
  ) {

    def timeToCustomer(passenger: PersonIdWithActorRef): Int =
      passengerSchedule.legsBeforePassengerBoards(passenger).map(_.duration).sum

    def travelTimeForCustomer(passenger: PersonIdWithActorRef): Int =
      passengerSchedule.legsWithPassenger(passenger).map(_.duration).sum

    /**
      * How far will the ride hail vehicle travel with the given customer as a passenger
      *
      * @param passenger PersonIdWithActorRef
      * @return distance in m
      */
    def travelDistanceForCustomer(passenger: PersonIdWithActorRef): Double =
      passengerSchedule.legsWithPassenger(passenger).map(_.travelPath.distanceInM).sum

    def toEmbodiedBeamLegsForCustomer(passenger: PersonIdWithActorRef): Vector[EmbodiedBeamLeg] = {
      passengerSchedule
        .legsWithPassenger(passenger)
        .map { beamLeg =>
          EmbodiedBeamLeg(
            beamLeg,
            rideHailAgentLocation.vehicleId,
            rideHailAgentLocation.vehicleType.id,
            asDriver = false,
            estimatedPrice(passenger.personId),
            unbecomeDriverOnCompletion = false,
            isPooledTrip = passengerSchedule.schedule.values.exists(_.riders.size > 1)
          )
        }
        .toVector
    }

    override def toString: String =
      s"RHA: ${rideHailAgentLocation.vehicleId}, price: $estimatedPrice, passengerSchedule: $passengerSchedule"
  }

  case class MarkVehicleBatteryDepleted(
    time: Int,
    vehicleId: Id[BeamVehicle]
  )

  case class RoutingResponses(
    tick: Int,
    routingResponses: Seq[RoutingResponse],
    triggerId: Long
  ) extends HasTriggerId

  case class PoolingInfo(timeFactor: Double, costFactor: Double)

  case class RegisterRideAvailable(rideHailAgent: ActorRef, vehicleId: Id[BeamVehicle], availableSince: SpaceTime)

  case class RegisterRideUnavailable(ref: ActorRef, location: Coord)

  case class RepositionResponse(
    rnd1: RideHailAgentLocation,
    rnd2: RideHailAgentLocation,
    rnd1Response: RoutingResponse,
    rnd2Response: RoutingResponse
  )

  case class RepositionVehicleRequest(
    passengerSchedule: PassengerSchedule,
    tick: Int,
    vehicleId: Id[BeamVehicle],
    rideHailAgent: RideHailAgentLocation,
    triggerId: Long
  ) extends HasTriggerId

  case class BufferedRideHailRequestsTrigger(tick: Int) extends Trigger

  case class RideHailRepositioningTrigger(tick: Int) extends Trigger

  case object DebugRideHailManagerDuringExecution

  case class ContinueBufferedRideHailRequests(tick: Int, triggerId: Long) extends HasTriggerId

  sealed trait RefuelSource

  case object JustArrivedAtDepot extends RefuelSource

  case object DequeuedToCharge extends RefuelSource

  final val fileBaseName = "rideHailInitialLocation"

  class OutputData extends OutputDataDescriptor {

    /**
      * Get description of fields written to the output files.
      *
      * @return list of data description objects
      */
    override def getOutputDataDescriptions(ioController: OutputDirectoryHierarchy): util.List[OutputDataDescription] = {
      val outputFilePath = ioController.getIterationFilename(0, fileBaseName + ".csv")
      val outputDirPath = ioController.getOutputPath
      val relativePath = outputFilePath.replace(outputDirPath, "")
      val list = new util.ArrayList[OutputDataDescription]
      list.add(
        OutputDataDescription(
          getClass.getSimpleName,
          relativePath,
          "rideHailAgentID",
          "Unique id of the given ride hail agent"
        )
      )
      list.add(
        OutputDataDescription(
          getClass.getSimpleName,
          relativePath,
          "xCoord",
          "X co-ordinate of the starting location of the ride hail"
        )
      )
      list.add(
        OutputDataDescription(
          getClass.getSimpleName,
          relativePath,
          "yCoord",
          "Y co-ordinate of the starting location of the ride hail"
        )
      )
      list
    }

  }

  case object DebugReport
}

class RideHailManager(
  val id: Id[VehicleManager],
  val beamServices: BeamServices,
  val beamScenario: BeamScenario,
  val transportNetwork: TransportNetwork,
  val tollCalculator: TollCalculator,
  val scenario: Scenario,
  val eventsManager: EventsManager,
  val scheduler: ActorRef,
  val router: ActorRef,
  val parkingManager: ActorRef,
  val chargingNetworkManager: ActorRef,
  val boundingBox: Envelope,
  val activityQuadTreeBounds: QuadTreeBounds,
  val surgePricingManager: RideHailSurgePricingManager,
  val tncIterationStats: Option[TNCIterationStats],
  val routeHistory: RouteHistory,
  val rideHailFleetInitializer: RideHailFleetInitializer,
  val rideHailParkingNetwork: RideHailDepotParkingManager[_]
) extends LoggingMessageActor
    with ActorLogging
    with Stash {

  implicit val timeout: Timeout = Timeout(50000, TimeUnit.SECONDS)
  implicit val debug: Debug = beamServices.beamConfig.beam.debug

  override val supervisorStrategy: OneForOneStrategy =
    OneForOneStrategy(maxNrOfRetries = 0) {
      case e: Exception =>
        log.error(e, s"Going to stop child of RHM because of ${e.getMessage}")
        Stop
      case _: AssertionError => Stop
    }

  /**
    * Customer inquiries awaiting reservation confirmation.
    */
  val rideHailManagerHelper: RideHailManagerHelper = new RideHailManagerHelper(this, boundingBox)

  val rand: Random = new Random(beamScenario.beamConfig.matsim.modules.global.randomSeed)

  lazy val travelProposalCache: Cache[String, TravelProposal] = {
    CacheBuilder
      .newBuilder()
      .maximumSize(
        5 * beamServices.matsimServices.getScenario.getPopulation.getPersons.size // ZN: Changed this from 10x ride hail fleet, which is now not directly set
      )
      .expireAfterWrite(1, TimeUnit.MINUTES)
      .build()
  }
  private val rideHailResponseCache = new mutable.HashMap[PersonIdWithActorRef, RideHailResponse]()

  def fleetSize: Int = resources.size

  val radiusInMeters: Double = beamServices.beamConfig.beam.agentsim.agents.rideHail.rideHailManager.radiusInMeters

  val rideHailNetworkApi: RideHailNetworkAPI = new RideHailNetworkAPI()

  val processBufferedRequestsOnTimeout: Boolean =
    beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.requestBufferTimeoutInSeconds > 0

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

  private val defaultBaseCost = beamServices.beamConfig.beam.agentsim.agents.rideHail.defaultBaseCost
  private val defaultCostPerMile = beamServices.beamConfig.beam.agentsim.agents.rideHail.defaultCostPerMile
  private val defaultCostPerMinute = beamServices.beamConfig.beam.agentsim.agents.rideHail.defaultCostPerMinute
  private val pooledBaseCost = beamServices.beamConfig.beam.agentsim.agents.rideHail.pooledBaseCost
  private val pooledCostPerMile = beamServices.beamConfig.beam.agentsim.agents.rideHail.pooledCostPerMile
  private val pooledCostPerMinute = beamServices.beamConfig.beam.agentsim.agents.rideHail.pooledCostPerMinute
  tncIterationStats.foreach(_.logMap())
  private val defaultCostPerSecond = defaultCostPerMinute / 60.0d
  private val pooledCostPerSecond = pooledCostPerMinute / 60.0d

  beamServices.beamCustomizationAPI.getRidehailManagerCustomizationAPI.init(this)
  val ridehailManagerCustomizationAPI = beamServices.beamCustomizationAPI.getRidehailManagerCustomizationAPI

  beamServices.beamRouter ! GetTravelTime
  beamServices.beamRouter ! GetMatSimNetwork
  //TODO improve search to take into account time when available
  private val pendingModifyPassengerScheduleAcks = mutable.HashMap[Int, RideHailResponse]()
  private var numPendingRoutingRequestsForReservations = 0
  private val parkingInquiryCache = collection.mutable.HashMap[Int, RideHailAgentLocation]()
  private val pendingAgentsSentToPark = collection.mutable.Set[Id[BeamVehicle]]()
  private val cachedNotifyVehicleIdle = collection.mutable.Map[Id[_], NotifyVehicleIdle]()

  private val cachedNotifyVehicleDoneRefuelingAndOffline =
    collection.mutable.Map[Id[_], NotifyVehicleDoneRefuelingAndOutOfService]()
  val doNotUseInAllocation: mutable.Set[Id[_]] = collection.mutable.Set[Id[_]]()

  // Tracking Inquiries and Reservation Requests
  val inquiryIdToInquiryAndResponse: mutable.Map[Int, (RideHailRequest, SingleOccupantQuoteAndPoolingInfo)] =
    mutable.Map()
  val routeRequestIdToRideHailRequestId: mutable.Map[Int, Int] = mutable.Map()
  val reservationIdToRequest: mutable.Map[Int, RideHailRequest] = mutable.Map()

  // Are we in the middle of processing a batch? or repositioning
  var currentlyProcessingTimeoutTrigger: Option[TriggerWithId] = None
  var currentlyProcessingTimeoutWallStartTime: Long = System.nanoTime()

  // Cache analysis
  private var cacheAttempts = 0
  private var cacheHits = 0

  private val rideHailinitialLocationSpatialPlot = new SpatialPlot(1100, 1100, 50)
  val resources: mutable.Map[Id[BeamVehicle], BeamVehicle] = mutable.Map[Id[BeamVehicle], BeamVehicle]()
  val maxTime = Time.parseTime(beamServices.beamScenario.beamConfig.beam.agentsim.endTime).toInt

  // generate or load parking using agentsim.infrastructure.parking.ParkingZoneSearch
  val parkingFilePath: String = beamServices.beamConfig.beam.agentsim.agents.rideHail.initialization.parking.filePath

  private val cntEVCAV = 0
  private val cntEVnCAV = 0
  private val cntnEVCAV = 0
  private val cntnEVnCAV = 0

  def writeMetric(metric: String, value: Int): Unit = {
    beamServices.simMetricCollector.writeGlobal(metric, value)
  }

  writeMetric("beam-run-RH-ev-cav", cntEVCAV)
  writeMetric("beam-run-RH-ev-non-cav", cntEVnCAV)
  writeMetric("beam-run-RH-non-ev-cav", cntnEVCAV)
  writeMetric("beam-run-RH-non-ev-non-cav", cntnEVnCAV)

  if (
    beamServices.matsimServices != null &&
    new File(
      beamServices.matsimServices.getControlerIO.getIterationPath(beamServices.matsimServices.getIterationNumber)
    ).exists()
  ) {
    rideHailinitialLocationSpatialPlot.writeCSV(
      beamServices.matsimServices.getControlerIO
        .getIterationFilename(beamServices.matsimServices.getIterationNumber, fileBaseName + ".csv")
    )

    if (beamServices.beamConfig.beam.outputs.writeGraphs) {
      rideHailinitialLocationSpatialPlot.writeImage(
        beamServices.matsimServices.getControlerIO
          .getIterationFilename(beamServices.matsimServices.getIterationNumber, fileBaseName + ".png")
      )
    }
  }

  private val rideHailResourceAllocationManager = RideHailResourceAllocationManager(
    beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.name,
    this
  )

  private val rideHailBeamVehicleIdToShiftsOpt = mutable.Map.empty[Id[BeamVehicle], Option[List[Shift]]]

  val numRideHailAgents: Int = initializeRideHailFleet()

  val realTimeKpis = new RealTimeKpis(beamServices, 300)

  var requestedRideHail: Int = 0
  var servedRideHail: Int = 0

  override def postStop: Unit = {
    log.info("postStop")
    log.info(s"requestedRideHail: $requestedRideHail")
    log.info(s"servedRideHail: $servedRideHail")
    log.info(s"ratio: ${servedRideHail.toDouble / requestedRideHail}")
    maybeDebugReport.foreach(_.cancel())
    log.info(
      s"timeSpendForHandleRideHailInquiryMs: $timeSpendForHandleRideHailInquiryMs ms, nHandleRideHailInquiry: $nHandleRideHailInquiry, AVG: ${timeSpendForHandleRideHailInquiryMs.toDouble / nHandleRideHailInquiry}"
    )
    log.info(
      s"timeSpendForFindAllocationsAndProcessMs: $timeSpendForFindAllocationsAndProcessMs ms, nFindAllocationsAndProcess: $nFindAllocationsAndProcess, AVG: ${timeSpendForFindAllocationsAndProcessMs.toDouble / nFindAllocationsAndProcess}"
    )
    super.postStop()
  }

  var timeSpendForHandleRideHailInquiryMs: Long = 0
  var nHandleRideHailInquiry: Int = 0

  var timeSpendForFindAllocationsAndProcessMs: Long = 0
  var nFindAllocationsAndProcess: Int = 0

  val maybeDebugReport: Option[Cancellable] = if (beamServices.beamConfig.beam.debug.debugEnabled) {
    Some(context.system.scheduler.scheduleWithFixedDelay(10.seconds, 30.seconds, self, DebugReport)(context.dispatcher))
  } else {
    None
  }

  var prevReposTick: Int = 0
  var currReposTick: Int = 0
  var nRepositioned: Int = 0

  override def loggedReceive: Receive = BeamLoggingReceive {
    case DebugReport =>
      log.debug(
        s"timeSpendForHandleRideHailInquiryMs: $timeSpendForHandleRideHailInquiryMs ms, nHandleRideHailInquiry: $nHandleRideHailInquiry, AVG: ${timeSpendForHandleRideHailInquiryMs.toDouble / nHandleRideHailInquiry}"
      )
      log.debug(
        s"timeSpendForFindAllocationsAndProcessMs: $timeSpendForFindAllocationsAndProcessMs ms, nFindAllocationsAndProcess: $nFindAllocationsAndProcess, AVG: ${timeSpendForFindAllocationsAndProcessMs.toDouble / nFindAllocationsAndProcess}"
      )

    case TriggerWithId(InitializeTrigger(_), triggerId) =>
      sender ! CompletionNotice(triggerId, Vector())

    case TAZSkimsCollectionTrigger(tick) =>
      rideHailManagerHelper.getIdleVehicles.foreach { case (_, agentLocation) =>
        val currentLocation = agentLocation.getCurrentLocationUTM(tick, beamServices)
        val skimmerEvent = TAZSkimmerEvent(
          tick,
          currentLocation,
          "idleRHVehicles",
          1.0,
          beamServices,
          "RideHailManager"
        )
        beamServices.matsimServices.getEvents.processEvent(skimmerEvent)
      }

      ridehailManagerCustomizationAPI.recordCollectionData(tick)

    case LogActorState =>
      ReflectionUtils.logFields(log, this, 0)
      ReflectionUtils.logFields(log, rideHailResourceAllocationManager, 0)
      ReflectionUtils.logFields(log, modifyPassengerScheduleManager, 0, "config")

    case RecoverFromStuckness(tick, triggerId) =>
      // This is assuming we are allocating demand and routes haven't been returned
      log.error(
        "Ride Hail Manager is abandoning dispatch of {} customers due to stuckness (routing response never received).",
        rideHailResourceAllocationManager.getUnprocessedCustomers.size
      )
      rideHailResourceAllocationManager.getUnprocessedCustomers.foreach { request =>
        modifyPassengerScheduleManager.addTriggerToSendWithCompletion(
          ScheduleTrigger(
            RideHailResponseTrigger(
              tick,
              RideHailResponse(
                request,
                None,
                Some(CouldNotFindRouteToCustomer)
              )
            ),
            request.customer.personRef
          )
        )
        rideHailResourceAllocationManager.removeRequestFromBuffer(request)
      }
      modifyPassengerScheduleManager.sendCompletionAndScheduleNewTimeout(BatchedReservation)
      rideHailResourceAllocationManager.clearPrimaryBufferAndFillFromSecondary
      log.debug("Cleaning up from RecoverFromStuckness")
      cleanUp(triggerId)

    case Finish =>
      if (beamServices.beamConfig.beam.agentsim.agents.rideHail.linkFleetStateAcrossIterations) {
        rideHailFleetInitializer.overrideRideHailAgentInitializers(createRideHailAgentInitializersFromCurrentState)
      }

      ridehailManagerCustomizationAPI.receiveFinishMessageHook()

      surgePricingManager.incrementIteration()
      context.children.foreach(_ ! Finish)
      dieIfNoChildren()
      contextBecome { case Terminated(_) =>
        dieIfNoChildren()
      }

    case NotifyVehicleOutOfService(vehicleId, _) =>
      rideHailParkingNetwork.notifyVehicleNoLongerOnWayToRefuelingDepot(vehicleId)
      rideHailManagerHelper.putOutOfService(vehicleId)

    case notify @ NotifyVehicleDoneRefuelingAndOutOfService(vehicleId, _, _, _, _)
        if currentlyProcessingTimeoutTrigger.isDefined =>
      cachedNotifyVehicleDoneRefuelingAndOffline.put(vehicleId, notify)

    case notify @ NotifyVehicleDoneRefuelingAndOutOfService(_, _, _, _, _) =>
      handleNotifyVehicleDoneRefuelingAndOutOfService(notify)

    case notify @ NotifyVehicleIdle(vehicleId, _, _, _, _, _) if currentlyProcessingTimeoutTrigger.isDefined =>
      // To avoid complexity, we don't add any new vehicles to the Idle list when we are in the middle of dispatch or repositioning
      // But we hold onto them because if we end up attempting to modify their passenger schedule, we need to first complete the notify
      // protocol so they can release their trigger.
      doNotUseInAllocation.add(vehicleId)
      cachedNotifyVehicleIdle.put(vehicleId, notify)

    case notifyVehicleIdleMessage @ NotifyVehicleIdle(_, _, _, _, _, _) =>
      handleNotifyVehicleIdle(notifyVehicleIdleMessage)

    case BeamVehicleStateUpdate(id, beamVehicleState) =>
      rideHailManagerHelper.vehicleState.put(id, beamVehicleState)

    case MATSimNetwork(network) =>
      rideHailNetworkApi.setMATSimNetwork(network)

    case inquiry @ RideHailRequest(RideHailInquiry, _, _, _, _, _, _, _, _, _, _) =>
      val s = System.currentTimeMillis
      handleRideHailInquiry(inquiry)
      val diff = System.currentTimeMillis - s
      nHandleRideHailInquiry += 1
      timeSpendForHandleRideHailInquiryMs += diff

    case R5Network(network) =>
      rideHailNetworkApi.setR5Network(network)

    /*
     * In the following case, we are calculating routes in batch for the allocation manager,
     * so we add these to the allocation buffer and then resume the allocation process.
     */
    case RoutingResponses(tick, responses, triggerId)
        if reservationIdToRequest.contains(routeRequestIdToRideHailRequestId(responses.head.requestId)) =>
      numPendingRoutingRequestsForReservations = numPendingRoutingRequestsForReservations - responses.size
      responses.foreach { routeResponse =>
        val request = reservationIdToRequest(routeRequestIdToRideHailRequestId(routeResponse.requestId))
        rideHailResourceAllocationManager.addRouteForRequestToBuffer(request, routeResponse)
      }
      self ! ContinueBufferedRideHailRequests(tick, triggerId)

    /*
     * Routing Responses from a Ride Hail Inquiry
     * In this case we can treat the responses as if they apply to a single request
     * for a single occupant trip.
     */
    case RoutingResponses(_, responses, _: Long)
        if inquiryIdToInquiryAndResponse.contains(routeRequestIdToRideHailRequestId(responses.head.requestId)) =>
      val (request, singleOccupantQuoteAndPoolingInfo) = inquiryIdToInquiryAndResponse(
        routeRequestIdToRideHailRequestId(responses.head.requestId)
      )

      // If any response contains no RIDE_HAIL legs, then the router failed
      val rideHailResponse: RideHailResponse =
        if (responses.exists(!_.itineraries.exists(_.tripClassifier.equals(RIDE_HAIL)))) {
          log.debug(
            "Router could not find route to customer person={} for requestId={}",
            request.customer.personId,
            request.requestId
          )
          RideHailResponse(
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
              .flatMap(_.itineraries.find(p => p.tripClassifier.equals(RIDE_HAIL)))
              .flatMap(_.legs)
              .toIndexedSeq
          )
          val driverPassengerSchedule = singleOccupantItinsToPassengerSchedule(request, embodiedBeamTrip)

          val baseFare = embodiedBeamTrip.legs
            .map(leg =>
              leg.cost - DrivingCost.estimateDrivingCost(
                leg.beamLeg.travelPath.distanceInM,
                leg.beamLeg.duration,
                beamScenario.vehicleTypes(leg.beamVehicleTypeId),
                beamScenario.fuelTypePrices(beamScenario.vehicleTypes(leg.beamVehicleTypeId).primaryFuelType)
              )
            )
            .sum

          val travelProposal = TravelProposal(
            singleOccupantQuoteAndPoolingInfo.rideHailAgentLocation,
            driverPassengerSchedule,
            calcFare(
              request,
              singleOccupantQuoteAndPoolingInfo.rideHailAgentLocation.vehicleType.id,
              driverPassengerSchedule,
              baseFare
            ),
            singleOccupantQuoteAndPoolingInfo.poolingInfo
          )
          travelProposalCache.put(request.requestId.toString, travelProposal)

          RideHailResponse(request, Some(travelProposal))
        }
      request.customer.personRef ! rideHailResponse
      rideHailResponseCache.get(request.customer) match {
        case Some(previousRideHailResponse) =>
          /* We log an error if an identical inquiry with a time stamp before the previously cached response as illogical
           * behavior, but we cannot make a stronger claim here that NO previous response is cached. This is because if an
           * agent makes an inquiry -- and the response is cached here -- but doesn't choose ride hail
           * as a mode, there is no simple way to remove that cached response inside the RHM. We can still safely
           * overwrite the response below with the latest.
           */
          if (previousRideHailResponse.request.departAt >= request.departAt) {
            log.error(
              s"Customer ${request.customer.personId} has made two RideHail Inquiries, with the departAt for the " +
              s"second being before or equal to the first: (${previousRideHailResponse.request.departAt} < ${request.departAt}. This is " +
              s"likely to cause logical errors."
            )
          }
        case None =>
      }
      rideHailResponseCache.put(request.customer, rideHailResponse)
      inquiryIdToInquiryAndResponse.remove(request.requestId)
      responses.foreach(routingResp => routeRequestIdToRideHailRequestId.remove(routingResp.requestId))

    case reserveRide @ RideHailRequest(ReserveRide, _, _, _, _, _, _, _, _, _, triggerId) =>
      handleReservationRequest(reserveRide, triggerId)

    case modifyPassengerScheduleAck @ ModifyPassengerScheduleAck(
          requestIdOpt,
          triggersToSchedule,
          vehicleId,
          tick,
          triggerId
        ) =>
      if (pendingAgentsSentToPark.contains(vehicleId)) {
        log.debug(
          "modifyPassengerScheduleAck received, handling with outOfServiceManager {}",
          modifyPassengerScheduleAck
        )
        outOfServiceVehicleManager.releaseTrigger(vehicleId, triggersToSchedule)
      } else {
        requestIdOpt match {
          case None =>
            // None here means this is part of repositioning, i.e. not tied to a reservation request
            log.debug(
              "modifyPassengerScheduleAck received, handling with modifyPassengerScheduleManager {}",
              modifyPassengerScheduleAck
            )
            modifyPassengerScheduleManager
              .modifyPassengerScheduleAckReceived(
                vehicleId,
                triggersToSchedule,
                triggerId
              )
          case Some(requestId) =>
            // Some here means this is part of a reservation / dispatch of vehicle to a customer
            log.debug("modifyPassengerScheduleAck received, completing reservation {}", modifyPassengerScheduleAck)
            completeReservation(requestId, tick, triggersToSchedule, triggerId)
        }
      }

    case UpdateTravelTimeLocal(travelTime) =>
      rideHailNetworkApi.setTravelTime(travelTime)

    case DebugRideHailManagerDuringExecution =>
      modifyPassengerScheduleManager.printState()

    case trigger @ TriggerWithId(BufferedRideHailRequestsTrigger(tick), triggerId) =>
      currentlyProcessingTimeoutTrigger match {
        case Some(_) =>
          log.debug("Stashing BufferedRideHailRequestsTrigger({})", tick)
          stash()
        case None =>
          currentlyProcessingTimeoutTrigger = Some(trigger)
          currentlyProcessingTimeoutWallStartTime = System.nanoTime()
          log.debug("Starting wave of buffered at {}", tick)
          rideHailManagerHelper.updateSpatialIndicesForMovingVehiclesToNewTick(tick)
          modifyPassengerScheduleManager.startWaveOfRepositioningOrBatchedReservationRequests(tick, triggerId)
          if (modifyPassengerScheduleManager.isModifyStatusCacheEmpty) {
            findAllocationsAndProcess(tick, triggerId)
          }
      }

    case ContinueBufferedRideHailRequests(tick, triggerId) =>
      // If modifyPassengerScheduleManager holds a tick, we're in buffered mode
      modifyPassengerScheduleManager.getCurrentTick match {
        case Some(workingTick) =>
          log.debug(
            "ContinueBuffer @ {} with buffer size {}",
            workingTick,
            rideHailResourceAllocationManager.getBufferSize
          )
          if (workingTick != tick) log.warning("Working tick {} but tick {}", workingTick, tick)
          findAllocationsAndProcess(workingTick, triggerId)
        case None if !processBufferedRequestsOnTimeout =>
          // this case is how we process non-buffered requests
          findAllocationsAndProcess(tick, triggerId)
        case _ =>
          log.error("Should not make it here")
      }

    case trigger @ TriggerWithId(RideHailRepositioningTrigger(tick), triggerId) =>
      //      DebugRepositioning.produceRepositioningDebugImages(tick, this)
      currentlyProcessingTimeoutTrigger match {
        case Some(_) =>
          stash()
        case None =>
          log.debug("Starting wave of repositioning at {}", tick)
          currentlyProcessingTimeoutTrigger = Some(trigger)
          currentlyProcessingTimeoutWallStartTime = System.nanoTime()
          startRepositioning(tick, triggerId)
      }

    case ReduceAwaitingRepositioningAckMessagesByOne(vehicleId, triggerId) =>
      modifyPassengerScheduleManager.cancelRepositionAttempt(vehicleId, triggerId)

    case MoveOutOfServiceVehicleToDepotParking(passengerSchedule, tick, vehicleId, triggerId) =>
      pendingAgentsSentToPark.add(vehicleId)
      outOfServiceVehicleManager.initiateMovementToParkingDepot(vehicleId, passengerSchedule, tick, triggerId)

    case RepositionVehicleRequest(passengerSchedule, tick, vehicleId, rideHailAgent, triggerId) =>
      if (isEligibleToReposition(vehicleId)) {
        modifyPassengerScheduleManager.sendNewPassengerScheduleToVehicle(
          passengerSchedule,
          rideHailAgent.vehicleId,
          rideHailAgent.rideHailAgent,
          tick,
          triggerId = triggerId
        )
      } else {
        // Failed attempt to reposition a car that is no longer idle
        modifyPassengerScheduleManager.cancelRepositionAttempt(vehicleId, triggerId)
      }

    case reply @ InterruptedWhileWaitingToDrive(_, vehicleId, tick, triggerId) =>
      // It's too complicated to modify these vehicles, it's also rare so we ignore them
      doNotUseInAllocation.add(vehicleId)
      modifyPassengerScheduleManager.handleInterruptReply(reply, triggerId)
      rideHailManagerHelper.updateLatestObservedTick(vehicleId, tick)
      continueProcessingTimeoutIfReady(triggerId)

    case reply @ InterruptedWhileOffline(_, vehicleId, tick, triggerId) =>
      doNotUseInAllocation.add(vehicleId)
      modifyPassengerScheduleManager.handleInterruptReply(reply, triggerId)
      rideHailManagerHelper.updateLatestObservedTick(vehicleId, tick)
      // Make sure we take away passenger schedule from RHA Location
      rideHailManagerHelper.updatePassengerSchedule(vehicleId, None, None)
      continueProcessingTimeoutIfReady(triggerId)

    case reply @ InterruptedWhileIdle(_, vehicleId, tick, triggerId) =>
      if (pendingAgentsSentToPark.contains(vehicleId)) {
        outOfServiceVehicleManager.handleInterruptReply(vehicleId, tick, triggerId)
      } else {
        modifyPassengerScheduleManager.handleInterruptReply(reply, triggerId)
        if (currentlyProcessingTimeoutTrigger.isDefined) rideHailManagerHelper.makeAvailable(vehicleId)
        rideHailManagerHelper.updateLatestObservedTick(vehicleId, tick)
        // Make sure we take away passenger schedule from RHA Location
        rideHailManagerHelper.updatePassengerSchedule(vehicleId, None, None)
        continueProcessingTimeoutIfReady(triggerId)
      }

    case reply @ InterruptedWhileDriving(
          _,
          vehicleId,
          tick,
          interruptedPassengerSchedule,
          currentPassengerScheduleIndex,
          triggerId
        ) =>
      if (pendingAgentsSentToPark.contains(vehicleId)) {
        log.error(
          "It is not expected in the current implementation that a moving vehicle would be stopped and sent for charging"
        )
      } else {
        modifyPassengerScheduleManager.handleInterruptReply(reply, triggerId)
        if (currentlyProcessingTimeoutTrigger.isDefined) rideHailManagerHelper.putIntoService(vehicleId)
        rideHailManagerHelper
          .updatePassengerSchedule(vehicleId, Some(interruptedPassengerSchedule), Some(currentPassengerScheduleIndex))
        rideHailManagerHelper.updateLatestObservedTick(vehicleId, tick)
        continueProcessingTimeoutIfReady(triggerId)
      }

    case ParkingInquiryResponse(stall, requestId, triggerId) =>
      val agentLocation = parkingInquiryCache.remove(requestId).get

      val routingRequest = RoutingRequest(
        originUTM = agentLocation.latestUpdatedLocationUTM.loc,
        destinationUTM = stall.locationUTM,
        departureTime = agentLocation.latestUpdatedLocationUTM.time,
        withTransit = false,
        personId = None,
        streetVehicles = Vector(agentLocation.toStreetVehicle),
        triggerId = triggerId
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
              triggerId: Long
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

    case msg =>
      ridehailManagerCustomizationAPI.receiveMessageHook(msg, sender())
  }

  def continueProcessingTimeoutIfReady(triggerId: Long): Unit = {
    if (modifyPassengerScheduleManager.allInterruptConfirmationsReceived) {
      throwRideHailFleetStateEvent(modifyPassengerScheduleManager.getCurrentTick.get)
      currentlyProcessingTimeoutTrigger.map(_.trigger) match {
        case Some(BufferedRideHailRequestsTrigger(_)) =>
          findAllocationsAndProcess(modifyPassengerScheduleManager.getCurrentTick.get, triggerId)
        case Some(RideHailRepositioningTrigger(_)) =>
          continueRepositioning(modifyPassengerScheduleManager.getCurrentTick.get, triggerId)
        case x =>
          log.warning(s"Have not expected to see '$x'")
      }
    }
  }

  def throwRideHailFleetStateEvent(tick: Int): Unit = {
    val inServiceRideHailVehicles = rideHailManagerHelper.inServiceRideHailVehicles.values
    val inServiceRideHailStateEvents = calculateCavEvs(inServiceRideHailVehicles, "InService", tick)
    eventsManager.processEvent(inServiceRideHailStateEvents)

    val outOfServiceRideHailVehicles = rideHailManagerHelper.outOfServiceRideHailVehicles.values
    val outOfServiceRideHailStateEvents = calculateCavEvs(outOfServiceRideHailVehicles, "offline", tick)
    eventsManager.processEvent(outOfServiceRideHailStateEvents)

    val idleRideHailEvents = rideHailManagerHelper.idleRideHailVehicles.values
    val idleRideHailStateEvents = calculateCavEvs(idleRideHailEvents, "idle", tick)
    eventsManager.processEvent(idleRideHailStateEvents)
  }

  def calculateCavEvs(
    rideHailAgentLocations: Iterable[RideHailAgentLocation],
    vehicleType: String,
    tick: Int
  ): RideHailFleetStateEvent = {
    val cavNonEvs = rideHailAgentLocations.count(rideHail =>
      rideHail.vehicleType.primaryFuelType != Electricity && rideHail.vehicleType.automationLevel > 3
    )
    val nonCavNonEvs = rideHailAgentLocations.count(rideHail =>
      rideHail.vehicleType.primaryFuelType != Electricity && rideHail.vehicleType.automationLevel <= 3
    )
    val cavEvs = rideHailAgentLocations.count(rideHail =>
      rideHail.vehicleType.primaryFuelType == Electricity && rideHail.vehicleType.automationLevel > 3
    )
    val nonCavEvs = rideHailAgentLocations.count(rideHail =>
      rideHail.vehicleType.primaryFuelType == Electricity && rideHail.vehicleType.automationLevel <= 3
    )
    new RideHailFleetStateEvent(tick, cavEvs, nonCavEvs, cavNonEvs, nonCavNonEvs, vehicleType)
  }

  def handleNotifyVehicleIdle(notifyVehicleIdleMessage: NotifyVehicleIdle): Unit = {
    val vehicleId = notifyVehicleIdleMessage.resourceId.asInstanceOf[Id[BeamVehicle]]
    log.debug(
      "RHM.NotifyVehicleIdle: {}, service status: {}",
      notifyVehicleIdleMessage,
      rideHailManagerHelper.getServiceStatusOf(vehicleId)
    )
    val (whenWhere, beamVehicleState, triggerId) = (
      notifyVehicleIdleMessage.whenWhere,
      notifyVehicleIdleMessage.beamVehicleState,
      notifyVehicleIdleMessage.triggerId
    )
    rideHailManagerHelper.updateLocationOfAgent(vehicleId, whenWhere)
    rideHailManagerHelper.vehicleState.put(vehicleId, beamVehicleState)
    rideHailManagerHelper.updatePassengerSchedule(vehicleId, None, None)

    val beamVehicle = resources(vehicleId)

    val vehicleArrivedAtTickAndStall =
      rideHailParkingNetwork.notifyVehicleNoLongerOnWayToRefuelingDepot(vehicleId).map((whenWhere.time, _))

    if (vehicleArrivedAtTickAndStall.isEmpty) {
      //If not arrived for refueling;
      log.debug("Making vehicle {} available", vehicleId)
      rideHailManagerHelper.makeAvailable(vehicleId)
    }

    beamVehicle.getDriver.get ! NotifyVehicleResourceIdleReply(triggerId, Seq.empty, vehicleArrivedAtTickAndStall)
  }

  def dieIfNoChildren(): Unit = {
    if (context.children.isEmpty) {
      log.info(
        "route request cache hits ({} / {}) or {}%",
        cacheHits,
        cacheAttempts,
        Math.round(cacheHits.toDouble / cacheAttempts.toDouble * 100)
      )
      rideHailResponseCache.clear()
      context.stop(self)
    } else {
      log.debug("Remaining: {}", context.children)
    }
  }

  def singleOccupantItinsToPassengerSchedule(
    request: RideHailRequest,
    embodiedTrip: EmbodiedBeamTrip
  ): PassengerSchedule = {
    val beamLegs = BeamLeg.makeLegsConsistent(embodiedTrip.toBeamTrip.legs.toList.map(Some(_))).flatten
    PassengerSchedule()
      .addLegs(beamLegs)
      .addPassenger(request.customer, beamLegs.tail)
  }

  def calcFare(
    request: RideHailRequest,
    rideHailVehicleTypeId: Id[BeamVehicleType],
    trip: PassengerSchedule,
    additionalCost: Double
  ): Map[Id[Person], Double] = {
    var costPerSecond = 0.0
    var costPerMile = 0.0
    var baseCost = 0.0
    if (request.asPooled) {
      costPerSecond = pooledCostPerSecond
      costPerMile = pooledCostPerMile
      baseCost = pooledBaseCost
    } else {
      costPerSecond = defaultCostPerSecond
      costPerMile = defaultCostPerMile
      baseCost = defaultBaseCost
    }
    val timeFare = costPerSecond * surgePricingManager
      .getSurgeLevel(
        request.pickUpLocationUTM,
        request.departAt
      ) * trip.legsWithPassenger(request.customer).map(_.duration).sum.toDouble
    val distanceFare = costPerMile * trip.schedule.keys.map(_.travelPath.distanceInM / 1609).sum

    val timeFareAdjusted = beamScenario.vehicleTypes.get(rideHailVehicleTypeId) match {
      case Some(vehicleType) if vehicleType.automationLevel > 3 =>
        0.0
      case _ =>
        timeFare
    }
    val fare = distanceFare + timeFareAdjusted + additionalCost + baseCost
    Map(request.customer.personId -> fare)
  }

  /* END: Refueling Logic */

  def handleRideHailInquiry(inquiry: RideHailRequest): Unit = {
    requestedRideHail += 1
    // Adjust depart time to account for delay from batch processing on a timeout, provides a more accurate quote
    val timeUntilNextDispatch = if (processBufferedRequestsOnTimeout) {
      val timeoutInterval =
        beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.requestBufferTimeoutInSeconds
      currentlyProcessingTimeoutTrigger match {
        case Some(triggerWithId) =>
          if (triggerWithId.trigger.tick > inquiry.departAt) {
            2 * timeoutInterval - (inquiry.departAt % timeoutInterval)
          } else {
            timeoutInterval - (inquiry.departAt % timeoutInterval)
          }
        case None =>
          timeoutInterval - (inquiry.departAt % timeoutInterval)
      }
    } else {
      0
    }
    val inquiryWithUpdatedLoc = RideHailRequest
      .projectCoordinatesToUtm(inquiry, beamServices)
      .copy(departAt = inquiry.departAt + timeUntilNextDispatch)
    rideHailResourceAllocationManager.respondToInquiry(inquiryWithUpdatedLoc) match {
      case NoVehiclesAvailable =>
        beamServices.simMetricCollector
          .writeIteration("ride-hail-inquiry-not-available", SimulationTime(inquiry.departAt))
        log.debug("{} -- NoVehiclesAvailable", inquiryWithUpdatedLoc.requestId)
        inquiryWithUpdatedLoc.customer.personRef ! RideHailResponse(
          inquiryWithUpdatedLoc,
          None,
          Some(DriverNotFoundError)
        )
      case inquiryResponse @ SingleOccupantQuoteAndPoolingInfo(agentLocation, _) =>
        servedRideHail += 1
        beamServices.simMetricCollector.writeIteration("ride-hail-inquiry-served", SimulationTime(inquiry.departAt))
        inquiryIdToInquiryAndResponse.put(inquiryWithUpdatedLoc.requestId, (inquiryWithUpdatedLoc, inquiryResponse))
        val routingRequests = createRoutingRequestsToCustomerAndDestination(
          inquiryWithUpdatedLoc.departAt,
          inquiryWithUpdatedLoc,
          agentLocation,
          inquiry.triggerId
        )
        routingRequests.foreach(rReq =>
          routeRequestIdToRideHailRequestId.put(rReq.requestId, inquiryWithUpdatedLoc.requestId)
        )
        requestRoutes(inquiryWithUpdatedLoc.departAt, routingRequests, inquiry.triggerId)
    }
  }

  // Returns true if pendingModifyPassengerScheduleAcks is empty and therefore signaling cleanup needed
  def cancelReservationDueToFailedModifyPassengerSchedule(requestId: Int): Boolean = {
    pendingModifyPassengerScheduleAcks.remove(requestId) match {
      case Some(rideHailResponse) =>
        log.debug("Removed request {} from pendingModifyPassengerScheduleAcks", requestId)
        val theTick = modifyPassengerScheduleManager.getCurrentTick.getOrElse(rideHailResponse.request.departAt)
        failedAllocation(rideHailResponse.request, theTick)
        pendingModifyPassengerScheduleAcks.isEmpty
      case None =>
        log.error("unexpected condition, canceling reservation but no pending modify pass schedule ack found")
        false
    }
  }

  def createRoutingRequestsToCustomerAndDestination(
    requestTime: Int,
    request: RideHailRequest,
    rideHailLocation: RideHailAgentLocation,
    triggerId: Long
  ): List[RoutingRequest] = {

    val pickupSpaceTime = SpaceTime((request.pickUpLocationUTM, request.departAt))
    //    val customerAgentBody =
    //      StreetVehicle(request.customer.vehicleId, pickupSpaceTime, WALK, asDriver = true)
    val rideHailVehicleAtOrigin = StreetVehicle(
      rideHailLocation.vehicleId,
      rideHailLocation.vehicleType.id,
      SpaceTime((rideHailLocation.getCurrentLocationUTM(requestTime, beamServices), requestTime)),
      CAR,
      asDriver = false,
      needsToCalculateCost = true
    )
    val rideHailVehicleAtPickup =
      StreetVehicle(
        rideHailLocation.vehicleId,
        rideHailLocation.vehicleType.id,
        pickupSpaceTime,
        CAR,
        asDriver = false,
        needsToCalculateCost = true
      )

    // route from ride hailing vehicle to customer
    val rideHailAgent2Customer = RoutingRequest(
      rideHailLocation.getCurrentLocationUTM(requestTime, beamServices),
      request.pickUpLocationUTM,
      requestTime,
      withTransit = false,
      Some(request.customer.personId),
      Vector(rideHailVehicleAtOrigin),
      triggerId = triggerId
    )
    // route from customer to destination
    val rideHail2Destination = RoutingRequest(
      request.pickUpLocationUTM,
      request.destinationUTM,
      requestTime,
      withTransit = false,
      Some(request.customer.personId),
      Vector(rideHailVehicleAtPickup),
      triggerId = triggerId
    )

    List(rideHailAgent2Customer, rideHail2Destination)
  }

  def requestRoutes(tick: Int, routingRequests: Seq[RoutingRequest], triggerId: Long): Unit = {
    cacheAttempts = cacheAttempts + 1
    val routeOrEmbodyReqs = routingRequests.map { rReq =>
      routeHistory.getRoute(
        beamServices.geo.getNearestR5EdgeToUTMCoord(transportNetwork.streetLayer, rReq.originUTM),
        beamServices.geo.getNearestR5EdgeToUTMCoord(transportNetwork.streetLayer, rReq.destinationUTM),
        rReq.departureTime
      ) match {
        case Some(rememberedRoute) =>
          cacheHits = cacheHits + 1
          val embodyReq = BeamRouter.linkIdsToEmbodyRequest(
            rememberedRoute,
            rReq.streetVehicles.head,
            rReq.departureTime,
            CAR,
            beamServices,
            rReq.originUTM,
            rReq.destinationUTM,
            Some(rReq.requestId),
            rReq.triggerId
          )
          RouteOrEmbodyRequest(None, Some(embodyReq))
        case None =>
          RouteOrEmbodyRequest(Some(rReq), None)
      }
    }
    Future
      .sequence(
        routeOrEmbodyReqs.map(req =>
          beam.utils.logging.pattern
            .ask(
              router,
              if (req.routeReq.isDefined) {
                req.routeReq.get
              } else {
                req.embodyReq.get
              }
            )
            .mapTo[RoutingResponse]
        )
      )
      .map(RoutingResponses(tick, _, triggerId)) pipeTo self
  }

  private def handleReservation(request: RideHailRequest, tick: Int, travelProposal: TravelProposal): Unit = {
    surgePricingManager.addRideCost(
      request.departAt,
      travelProposal.estimatedPrice(request.customer.personId),
      request.pickUpLocationUTM
    )
    // Track remaining seats available
    rideHailManagerHelper.putIntoService(
      travelProposal.rideHailAgentLocation
        .copy(currentPassengerSchedule = Some(travelProposal.passengerSchedule), servingPooledTrip = request.asPooled)
    )
    val rideHailResponse = RideHailResponse(request, Some(travelProposal))
    // Create confirmation info but stash until we receive ModifyPassengerScheduleAck
    pendingModifyPassengerScheduleAcks.put(
      request.requestId,
      rideHailResponse
    )

    beamServices.simMetricCollector.writeIteration("ride-hail-allocation-reserved", SimulationTime(tick))
    log.debug(
      "Reserving vehicle: {} customer: {} request: {} pendingAcks: {}",
      travelProposal.rideHailAgentLocation.vehicleId,
      request.customer.personId,
      request.requestId,
      s"(${pendingModifyPassengerScheduleAcks.size})" //${pendingModifyPassengerScheduleAcks.keySet.map(_.toString).mkString(",")}"
    )
    log.debug(
      "Num in service: {}, num idle: {}",
      rideHailManagerHelper.inServiceRideHailVehicles.size,
      rideHailManagerHelper.idleRideHailVehicles.size
    )
    cachedNotifyVehicleIdle.get(travelProposal.rideHailAgentLocation.vehicleId) match {
      case Some(notifyVehicleIdle) =>
        handleNotifyVehicleIdle(notifyVehicleIdle)
        modifyPassengerScheduleManager.setStatusToIdle(
          notifyVehicleIdle.resourceId.asInstanceOf[Id[BeamVehicle]],
          request.triggerId
        )
        cachedNotifyVehicleIdle.remove(travelProposal.rideHailAgentLocation.vehicleId)
      case None =>
    }
    modifyPassengerScheduleManager.sendNewPassengerScheduleToVehicle(
      travelProposal.passengerSchedule,
      travelProposal.rideHailAgentLocation.vehicleId,
      travelProposal.rideHailAgentLocation.rideHailAgent,
      tick,
      request.triggerId,
      Some(request.requestId)
    )
  }

  private def completeReservation(
    requestId: Int,
    tick: Int,
    finalTriggersToSchedule: Vector[ScheduleTrigger],
    triggerId: Long
  ): Unit = {
    if (log.isDebugEnabled) {
      log.debug(
        "Removing request: {} pendingAcks: {} pendingRoutes: {} requestBufferSize: {}",
        requestId,
        s"(${pendingModifyPassengerScheduleAcks.size}) ${pendingModifyPassengerScheduleAcks.keySet.map(_.toString).mkString(",")}",
        numPendingRoutingRequestsForReservations,
        rideHailResourceAllocationManager.getBufferSize
      )
    }
    pendingModifyPassengerScheduleAcks.remove(requestId) match {
      case Some(response) =>
        val theVehicle = response.travelProposal.get.rideHailAgentLocation.vehicleId
        log.debug(
          "Completing reservation {} for customer {} and vehicle {}",
          requestId,
          response.request.customer.personId,
          theVehicle
        )
        val directTrip =
          rideHailResponseCache.remove(response.request.customer).flatMap(_.travelProposal)
        if (processBufferedRequestsOnTimeout) {
          modifyPassengerScheduleManager.addTriggersToSendWithCompletion(finalTriggersToSchedule)
          response.request.customer.personRef ! response.copy(
            triggersToSchedule = Vector(),
            directTripTravelProposal = directTrip
          )
          response.request.groupedWithOtherRequests.foreach { subReq =>
            val subDirectTrip =
              rideHailResponseCache.remove(subReq.customer).flatMap(_.travelProposal)
            subReq.customer.personRef ! response.copy(
              request = subReq,
              triggersToSchedule = Vector(),
              directTripTravelProposal = subDirectTrip
            )
          }
        } else {
          response.request.customer.personRef ! response.copy(
            triggersToSchedule = finalTriggersToSchedule,
            directTripTravelProposal = directTrip
          )
        }
        modifyPassengerScheduleManager.clearModifyStatusFromCacheWithVehicleId(
          response.travelProposal.get.rideHailAgentLocation.vehicleId
        )
        // The following is an API call to allow implementing class to process or cleanup
        rideHailResourceAllocationManager.reservationCompletionNotice(response.request.customer.personId, theVehicle)
      case None =>
        log.error("Vehicle was reserved by another agent for inquiry id {}", requestId)
        sender() ! RideHailResponse.dummyWithError(RideHailVehicleTakenError)
    }
    if (processBufferedRequestsOnTimeout && currentlyProcessingTimeoutTrigger.isDefined) {
      if (pendingModifyPassengerScheduleAcks.isEmpty) {
        log.debug("Cleaning up and completing batch processing @ {}", tick)
        cleanUpBufferedRequestProcessing(triggerId)
      }
    }
  }

  private def handleReservationRequest(request: RideHailRequest, triggerId: Long): Unit = {
    // Batched processing first
    if (processBufferedRequestsOnTimeout) {
      if (currentlyProcessingTimeoutTrigger.isDefined) {
        // We store these in a secondary buffer so that we **don't** process them in this round but wait for the
        // next timeout
        rideHailResourceAllocationManager.addRequestToSecondaryBuffer(request)
      } else {
        rideHailResourceAllocationManager.addRequestToBuffer(request)
      }
      request.customer.personRef ! DelayedRideHailResponse
    } else {
      if (currentlyProcessingTimeoutTrigger.isEmpty) {
        // We always use the request buffer even if we will process these requests immediately
        rideHailResourceAllocationManager.addRequestToBuffer(request)
        findAllocationsAndProcess(request.departAt, triggerId)
      } else {
        // We're in middle of repositioning, so stash this message until we're done (method "cleanup" called)
        stash()
      }
    }
  }

  /**
    * Initializes the ride hail fleet by getting the initial fleet information from the RideHailFleetInitializer and
    * creating the necessary agents.
    *
    * @return Number of vehicles in the ride hail fleet
    */
  private def initializeRideHailFleet(): Int = {
    val rideHailAgentInitializers = rideHailFleetInitializer.getRideHailAgentInitializers(id, activityQuadTreeBounds)

    rideHailAgentInitializers.foreach { rideHailAgentInitializer =>
      createRideHailVehicleAndAgent(rideHailAgentInitializer)
    }

    log.info("Initialized {} ride hailing agents", rideHailAgentInitializers.size)
    RideHailFleetInitializer.writeFleetData(
      beamServices,
      rideHailAgentInitializers.map(_.createRideHailAgentInputData),
      "rideHailFleet.csv.gz"
    )

    beamServices.beamCustomizationAPI.getRidehailManagerCustomizationAPI
      .initializeRideHailFleetHook(beamServices, rideHailAgentInitializers, maxTime)

    rideHailParkingNetwork.registerGeofences(resources.map { case (vehicleId, _) =>
      vehicleId -> rideHailManagerHelper.getRideHailAgentLocation(vehicleId).geofence
    })

    rideHailAgentInitializers.size
  }

  /**
    * Creates a ride hail agent and vehicle based on initialization.
    *
    * @param rideHailAgentInitializer Initialzation parameters for the ride hail agent.
    */
  private def createRideHailVehicleAndAgent(rideHailAgentInitializer: RideHailAgentInitializer): Unit = {

    val rideHailBeamVehicle = rideHailAgentInitializer.createBeamVehicle(Some(self), rand.nextInt())
    resources += (rideHailBeamVehicle.id -> rideHailBeamVehicle)
    rideHailManagerHelper.vehicleState.put(rideHailBeamVehicle.id, rideHailBeamVehicle.getState)

    val rideHailAgentProps: Props = RideHailAgent.props(
      beamServices,
      beamScenario,
      scheduler,
      transportNetwork,
      tollCalculator,
      eventsManager,
      parkingManager,
      chargingNetworkManager,
      rideHailAgentInitializer.rideHailAgentId,
      self,
      rideHailBeamVehicle,
      rideHailAgentInitializer.shifts,
      rideHailAgentInitializer.geofence
    )

    val rideHailAgentRef: ActorRef =
      context.actorOf(rideHailAgentProps, rideHailAgentInitializer.rideHailAgentId.toString)
    context.watch(rideHailAgentRef)
    scheduler ! ScheduleTrigger(InitializeTrigger(0), rideHailAgentRef)

    val agentLocation = RideHailAgentLocation(
      rideHailAgentRef,
      rideHailBeamVehicle.id,
      rideHailBeamVehicle.beamVehicleType,
      SpaceTime(rideHailAgentInitializer.initialLocation, 0),
      rideHailAgentInitializer.geofence,
      None,
      None
    )
    // Put the agent out of service and let the agent tell us when it's Idle (aka ready for service)
    rideHailManagerHelper.putOutOfService(agentLocation)

    rideHailBeamVehicleIdToShiftsOpt(rideHailAgentInitializer.beamVehicleId) = rideHailAgentInitializer.shifts

    rideHailinitialLocationSpatialPlot
      .addString(
        StringToPlot(s"${rideHailAgentInitializer.id}", rideHailAgentInitializer.initialLocation, Color.RED, 20)
      )
    rideHailinitialLocationSpatialPlot
      .addAgentWithCoord(
        RideHailAgentInitCoord(rideHailAgentInitializer.rideHailAgentId, rideHailAgentInitializer.initialLocation)
      )
  }

  /**
    * Creates a sequence of RideHailAgentInitializer that allow initializing the ride hail fleet to its current state.
    *
    * @return Sequence of RideHailAgentInitializer mirroring the current fleet state.
    */
  private def createRideHailAgentInitializersFromCurrentState: IndexedSeq[RideHailAgentInitializer] = {
    rideHailManagerHelper.vehicleState.toIndexedSeq.map {
      case (vehicleId: Id[BeamVehicle], beamVehicleState: BeamVehicleState) =>
        val rideHailVehicleId = RideHailVehicleId(vehicleId)

        val rideHailAgentLocation = rideHailManagerHelper.getRideHailAgentLocation(vehicleId)

        val shiftsOpt = rideHailBeamVehicleIdToShiftsOpt(vehicleId)

        if (rideHailAgentLocation.vehicleType.secondaryFuelType.isDefined) {
          // The concept of linking SOC across iterations is implemented for BEVs only.
          // (Needs to be implemented for PHEVs.)
          throw new RuntimeException(
            "Creation of RideHailAgentInitializers for linking across iterations has not been tested for PHEVs."
          )
        }
        val stateOfCharge =
          beamVehicleState.primaryFuelLevel / rideHailAgentLocation.vehicleType.primaryFuelCapacityInJoule

        RideHailAgentInitializer(
          rideHailVehicleId.id,
          rideHailAgentLocation.vehicleType,
          id,
          shiftsOpt,
          stateOfCharge,
          rideHailAgentLocation.latestUpdatedLocationUTM.loc,
          rideHailAgentLocation.geofence,
          rideHailVehicleId.fleetId
        )
    }
  }

  private def getDispatchProductType(tick: Int) = {
    val allocationManagerConfig = beamScenario.beamConfig.beam.agentsim.agents.rideHail.allocationManager
    val pooledRideHailIntervalAsMultipleOfSoloRideHail =
      allocationManagerConfig.pooledRideHailIntervalAsMultipleOfSoloRideHail
    val pooledTimeOut =
      allocationManagerConfig.requestBufferTimeoutInSeconds * pooledRideHailIntervalAsMultipleOfSoloRideHail

    if (pooledRideHailIntervalAsMultipleOfSoloRideHail <= 1) {
      DispatchProductType.SOLO_AND_POOLED
    } else if (tick % pooledTimeOut == 0) {
      DispatchProductType.POOLED
    } else {
      DispatchProductType.SOLO
    }
  }

  /*
   * This is common code for both use cases, batch processing and processing a single reservation request immediately.
   * The differences are resolved through the boolean processBufferedRequestsOnTimeout.
   */
  private def findAllocationsAndProcess(tick: Int, triggerId: Long): Unit = {
    val s = System.currentTimeMillis()
    var allRoutesRequired: Vector[RoutingRequest] = Vector()
    log.debug("findAllocationsAndProcess @ {}", tick)

    rideHailResourceAllocationManager.allocateVehiclesToCustomers(
      tick,
      beamServices,
      getDispatchProductType(tick),
      triggerId
    ) match {
      case VehicleAllocations(allocations) =>
        allocations.foreach {
          case RoutingRequiredToAllocateVehicle(request, routesRequired) =>
            // Client has requested routes
            reservationIdToRequest.put(request.requestId, request)
            routesRequired.foreach(rReq => routeRequestIdToRideHailRequestId.put(rReq.requestId, request.requestId))
            allRoutesRequired = allRoutesRequired ++ routesRequired
          case alloc @ VehicleMatchedToCustomers(request, _, pickDropIdWithRoutes) if pickDropIdWithRoutes.nonEmpty =>
            val travelProposal = createTravelProposal(alloc)
            val waitTimeMaximumSatisfied = !travelProposal.passengerSchedule.uniquePassengers.exists { customer =>
              travelProposal.timeToCustomer(
                customer
              ) > beamScenario.beamConfig.beam.agentsim.agents.rideHail.allocationManager.maxWaitingTimeInSec
            }
            if (waitTimeMaximumSatisfied) {
              handleReservation(request, tick, travelProposal)
              rideHailResourceAllocationManager.removeRequestFromBuffer(request)
            } else {
              beamServices.simMetricCollector.writeIteration("ride-hail-allocation-failed", SimulationTime(tick))
              failedAllocation(request, tick)
            }
          case VehicleMatchedToCustomers(request, _, _) =>
            beamServices.simMetricCollector.writeIteration("ride-hail-allocation-failed", SimulationTime(tick))
            failedAllocation(request, tick)
          case NoVehicleAllocated(request) =>
            beamServices.simMetricCollector.writeIteration("ride-hail-allocation-failed", SimulationTime(tick))
            failedAllocation(request, tick)
        }
      case _ =>
    }
    if (allRoutesRequired.nonEmpty) {
      log.debug("requesting {} routes at {}", allRoutesRequired.size, tick)
      numPendingRoutingRequestsForReservations = numPendingRoutingRequestsForReservations + allRoutesRequired.size
      requestRoutes(tick, allRoutesRequired, triggerId)
    } else if (
      processBufferedRequestsOnTimeout && pendingModifyPassengerScheduleAcks.isEmpty &&
      rideHailResourceAllocationManager.isBufferEmpty && numPendingRoutingRequestsForReservations == 0 &&
      currentlyProcessingTimeoutTrigger.isDefined
    ) {
      log.debug("sendCompletionAndScheduleNewTimeout for tick {} from line 1072", tick)
      cleanUpBufferedRequestProcessing(triggerId)
    }
    val diff = System.currentTimeMillis() - s
    timeSpendForFindAllocationsAndProcessMs += diff
    nFindAllocationsAndProcess += 1
  }

  //TODO this doesn't distinguish fare by customer, lumps them all together
  def createTravelProposal(alloc: VehicleMatchedToCustomers): TravelProposal = {
    val passSched = mobilityRequestToPassengerSchedule(alloc.schedule, alloc.rideHailAgentLocation)
    val updatedPassengerSchedule =
      ridehailManagerCustomizationAPI.updatePassengerScheduleDuringCreateTravelProposalHook(passSched)

    val baseFare = alloc.schedule
      .flatMap(
        _.beamLegAfterTag.map(leg =>
          leg.cost - DrivingCost.estimateDrivingCost(
            leg.beamLeg.travelPath.distanceInM,
            leg.beamLeg.duration,
            beamScenario.vehicleTypes(leg.beamVehicleTypeId),
            beamScenario.fuelTypePrices(beamScenario.vehicleTypes(leg.beamVehicleTypeId).primaryFuelType)
          )
        )
      )
      .sum

    TravelProposal(
      alloc.rideHailAgentLocation,
      updatedPassengerSchedule,
      calcFare(alloc.request, alloc.rideHailAgentLocation.vehicleType.id, updatedPassengerSchedule, baseFare),
      None
    )
  }

  def mobilityRequestToPassengerSchedule(
    pickDrops: List[MobilityRequest],
    rideHailAgentLocation: RideHailAgentLocation
  ): PassengerSchedule = {
    val consistentSchedule = pickDrops.zip(BeamLeg.makeLegsConsistent(pickDrops.map(_.beamLegAfterTag.map(_.beamLeg))))
    val allLegs = consistentSchedule.flatMap(_._2)
    var passSched = PassengerSchedule()
      .addLegs(allLegs)
      .updateStartTimes(Math.max(allLegs.head.startTime, rideHailAgentLocation.latestTickExperienced))
    // Initialize passengersToAdd with any passenger that doesn't have a pickup
    val noPickupPassengers = Set[PersonIdWithActorRef]() ++ consistentSchedule
      .groupBy(_._1.person)
      .filter(tup => tup._1.isDefined && tup._2.size == 1)
      .map(_._2.head._1.person.get)
    var passengersToAdd = noPickupPassengers
    var pickDropsForGrouping: Map[PersonIdWithActorRef, List[BeamLeg]] = Map()
    consistentSchedule.foreach {
      case (mobReq, legOpt) =>
        mobReq.person.foreach { thePerson =>
          mobReq.tag match {
            case Pickup =>
              passengersToAdd = passengersToAdd + thePerson
            case Dropoff =>
              passengersToAdd = passengersToAdd - thePerson
            case _ =>
          }
        }
        legOpt.foreach { leg =>
          passengersToAdd.foreach { pass =>
            val legsForPerson = pickDropsForGrouping.getOrElse(pass, List()) :+ leg
            pickDropsForGrouping = pickDropsForGrouping + (pass -> legsForPerson)
          }
        }
      case _ =>
    }
    pickDropsForGrouping.foreach { passAndLegs =>
      passSched = passSched.addPassenger(passAndLegs._1, passAndLegs._2)
    }
    noPickupPassengers.foreach { pass =>
      passSched = passSched.removePassengerBoarding(pass)
    }
    passSched
  }

  def failedAllocation(request: RideHailRequest, tick: Int): Unit = {
    val theResponse = RideHailResponse(request, None, Some(DriverNotFoundError))
    if (processBufferedRequestsOnTimeout) {
      modifyPassengerScheduleManager.addTriggerToSendWithCompletion(
        ScheduleTrigger(
          RideHailResponseTrigger(tick, theResponse),
          request.customer.personRef
        )
      )
      request.groupedWithOtherRequests.foreach { subReq =>
        modifyPassengerScheduleManager.addTriggerToSendWithCompletion(
          ScheduleTrigger(
            RideHailResponseTrigger(tick, theResponse),
            subReq.customer.personRef
          )
        )
      }
    } else {
      request.customer.personRef ! theResponse
      request.groupedWithOtherRequests.foreach { subReq =>
        subReq.customer.personRef ! theResponse
      }
    }
    rideHailResponseCache.remove(request.customer)
    request.groupedWithOtherRequests.foreach { subReq =>
      rideHailResponseCache.remove(subReq.customer)
    }
    rideHailResourceAllocationManager.removeRequestFromBuffer(request)
  }

  def cleanUpBufferedRequestProcessing(triggerId: Long): Unit = {
    rideHailResourceAllocationManager.clearPrimaryBufferAndFillFromSecondary
    modifyPassengerScheduleManager.sendCompletionAndScheduleNewTimeout(BatchedReservation)
    log.debug("Cleaning up from cleanUpBufferedRequestProcessing")
    cleanUp(triggerId)
  }

  def handleNotifyVehicleDoneRefuelingAndOutOfService(notify: NotifyVehicleDoneRefuelingAndOutOfService): Unit = {
    rideHailManagerHelper.updateLocationOfAgent(notify.vehicleId, notify.whenWhere)
    rideHailManagerHelper.vehicleState.put(notify.vehicleId, notify.beamVehicleState)
    rideHailManagerHelper.updatePassengerSchedule(notify.vehicleId, None, None)

    val vehicleArrivedAtTickAndStall =
      rideHailParkingNetwork
        .notifyVehicleNoLongerOnWayToRefuelingDepot(notify.vehicleId)
        .map((notify.whenWhere.time, _))

    if (vehicleArrivedAtTickAndStall.isEmpty) {
      //If not arrived for refueling;
      log.debug("Making vehicle {} available", notify.vehicleId)
      rideHailManagerHelper.makeAvailable(notify.vehicleId)
    }

    resources(notify.vehicleId).getDriver.get ! NotifyVehicleDoneRefuelingAndOutOfServiceReply(
      notify.triggerId,
      Seq.empty,
      vehicleArrivedAtTickAndStall
    )
    rideHailManagerHelper.putOutOfService(notify.vehicleId)
  }

  def cleanUp(triggerId: Long): Unit = {
    modifyPassengerScheduleManager.cleanUpCaches(triggerId)
    cachedNotifyVehicleIdle.foreach { case (_, notifyMessage) =>
      handleNotifyVehicleIdle(notifyMessage)
    }
    cachedNotifyVehicleIdle.clear()
    cachedNotifyVehicleDoneRefuelingAndOffline.foreach { case (_, notifyMessage) =>
      handleNotifyVehicleDoneRefuelingAndOutOfService(notifyMessage)
    }
    cachedNotifyVehicleDoneRefuelingAndOffline.clear()
    log.debug("Elapsed planning time = {}", (System.nanoTime() - currentlyProcessingTimeoutWallStartTime) / 1e6)
    currentlyProcessingTimeoutTrigger = None
    doNotUseInAllocation.clear()
    unstashAll()
  }

  def startRepositioning(tick: Int, triggerId: Long): Unit = {
    if (prevReposTick == 0) {
      prevReposTick = tick
    }
    currReposTick = tick

    log.debug("Starting wave of repositioning at {}", tick)
    modifyPassengerScheduleManager.startWaveOfRepositioningOrBatchedReservationRequests(tick, triggerId)
    if (modifyPassengerScheduleManager.isModifyStatusCacheEmpty) {
      continueRepositioning(tick, triggerId)
    }
  }

  def continueRepositioning(tick: Int, triggerId: Long): Unit = {
    ridehailManagerCustomizationAPI.beforeContinueRepositioningHook(tick)

    var idleVehicles: mutable.Map[Id[BeamVehicle], RideHailAgentLocation] =
      rideHailManagerHelper.getIdleAndRepositioningAndOfflineCAVsAndFilterOutExluded.filterNot(veh =>
        rideHailParkingNetwork.isOnWayToRefuelingDepotOrIsRefuelingOrInQueue(veh._1)
      )

    val badVehicles =
      rideHailManagerHelper.getIdleAndRepositioningAndOfflineCAVsAndFilterOutExluded
        .filter(veh => rideHailParkingNetwork.isOnWayToRefuelingDepotOrIsRefuelingOrInQueue(veh._1))
        .map(tup => (tup, rideHailManagerHelper.getServiceStatusOf(tup._1)))

    if (badVehicles.nonEmpty) {
      log.debug(
        f"Some vehicles (${badVehicles.size}) still appear as 'idle' despite being on way to refuel or refueling, head: ${badVehicles.head}"
      )
    }

    val additionalCustomVehiclesForDepotCharging = ridehailManagerCustomizationAPI
      .identifyAdditionalVehiclesForRefuelingDuringContinueRepositioningAndAssignDepotHook(idleVehicles, tick)

    val vehiclesWithoutCustomVehicles = idleVehicles.filterNot { case (vehicleId, _) =>
      additionalCustomVehiclesForDepotCharging.map(_._1).contains(vehicleId)
    }

    val vehicleChargingManagerResult = rideHailParkingNetwork
      .findStationsForVehiclesInNeedOfCharging(tick, resources, vehiclesWithoutCustomVehicles, beamServices)
    val candidateVehiclesHeadedToRefuelingDepot =
      vehicleChargingManagerResult ++ additionalCustomVehiclesForDepotCharging

    val vehiclesHeadedToRefuelingDepot: Vector[(VehicleId, ParkingStall)] =
      candidateVehiclesHeadedToRefuelingDepot
        .filter { case (vehicleId, _) =>
          val vehicleIsIdle = idleVehicles.contains(vehicleId)
          if (!vehicleIsIdle) {
            log.warning(
              f"$vehicleId was sent to refuel but it is not idle." +
              f"Request will be ignored."
            )
          }
          vehicleIsIdle
        }
        .filter { case (vehId, parkingStall) =>
          val maybeGeofence = rideHailManagerHelper.getRideHailAgentLocation(vehId).geofence
          val isInsideGeofence =
            maybeGeofence.forall { g =>
              val locUTM = beamServices.geo.wgs2Utm(
                beamServices.geo.snapToR5Edge(
                  beamServices.beamScenario.transportNetwork.streetLayer,
                  beamServices.geo.utm2Wgs(parkingStall.locationUTM)
                )
              )
              g.contains(locUTM.getX, locUTM.getY)
            }
          if (!isInsideGeofence) {
            log.warning(
              f"$vehId was sent to refuel at $parkingStall which is outside it geofence. " +
              f"Request will be ignored."
            )
          }

          isInsideGeofence
        }

    rideHailParkingNetwork.notifyVehiclesOnWayToRefuelingDepot(vehiclesHeadedToRefuelingDepot)
    vehiclesHeadedToRefuelingDepot.foreach { case (vehicleId, _) =>
      doNotUseInAllocation.add(vehicleId)
      rideHailManagerHelper.putRefueling(vehicleId)
    }

    idleVehicles = rideHailManagerHelper.getIdleAndRepositioningVehiclesAndFilterOutExluded

    val nonRefuelingRepositionVehicles: Vector[(VehicleId, Location)] =
      rideHailResourceAllocationManager.repositionVehicles(idleVehicles, tick)

    val insideGeofence = nonRefuelingRepositionVehicles.filter { case (vehicleId, destLoc) =>
      val rha = rideHailManagerHelper.getRideHailAgentLocation(vehicleId)
      // Get locations of R5 edge for source and destination
      val r5SrcLocUTM = beamServices.geo.wgs2Utm(
        beamServices.geo.snapToR5Edge(
          beamServices.beamScenario.transportNetwork.streetLayer,
          beamServices.geo.utm2Wgs(rha.getCurrentLocationUTM(tick, beamServices))
        )
      )
      val r5DestLocUTM = beamServices.geo.wgs2Utm(
        beamServices.geo
          .snapToR5Edge(beamServices.beamScenario.transportNetwork.streetLayer, beamServices.geo.utm2Wgs(destLoc))
      )
      // Are those locations inside geofence?
      val isSrcInside = rha.geofence.forall(g => g.contains(r5SrcLocUTM))
      val isDestInside = rha.geofence.forall(g => g.contains(r5DestLocUTM))
      isSrcInside && isDestInside
    }
    log.debug(
      "continueRepositionig. Tick[{}] nonRefuelingRepositionVehicles: {}, insideGeofence: {}",
      tick,
      nonRefuelingRepositionVehicles.size,
      insideGeofence.size
    )

    val repositionVehicles: Vector[(VehicleId, Location)] = insideGeofence ++ vehiclesHeadedToRefuelingDepot.map {
      case (vehicleId, parkingStall) => (vehicleId, parkingStall.locationUTM)
    }

    if (repositionVehicles.isEmpty) {
      log.debug("sendCompletionAndScheduleNewTimeout from 1486")
      modifyPassengerScheduleManager.sendCompletionAndScheduleNewTimeout(Reposition)
      cleanUp(triggerId)
    } else {
      val toReposition = repositionVehicles.map(_._1).toSet
      modifyPassengerScheduleManager.setRepositioningsToProcess(toReposition)
    }

    val futureRepoRoutingMap = mutable.Map[Id[BeamVehicle], Future[RoutingRequest]]()

    for ((vehicleId, destinationLocation) <- repositionVehicles) {
      rideHailManagerHelper.getServiceStatusOf(vehicleId) match {
        case _ =>
          val rideHailAgentLocation = rideHailManagerHelper.getRideHailAgentLocation(vehicleId)

          val rideHailVehicleAtOrigin = StreetVehicle(
            rideHailAgentLocation.vehicleId,
            rideHailAgentLocation.vehicleType.id,
            SpaceTime((rideHailAgentLocation.getCurrentLocationUTM(tick, beamServices), tick)),
            CAR,
            asDriver = false,
            needsToCalculateCost = true
          )
          val routingRequest = RoutingRequest(
            originUTM = rideHailAgentLocation.getCurrentLocationUTM(tick, beamServices),
            destinationUTM = destinationLocation,
            departureTime = tick,
            withTransit = false,
            personId = None,
            streetVehicles = Vector(rideHailVehicleAtOrigin),
            triggerId = triggerId
          )
          val futureRideHailAgent2CustomerResponse = router ? routingRequest
          futureRepoRoutingMap.put(vehicleId, futureRideHailAgent2CustomerResponse.asInstanceOf[Future[RoutingRequest]])

      }
    }
    for {
      (vehicleId, futureRoutingRequest) <- futureRepoRoutingMap
      rideHailAgent2CustomerResponse    <- futureRoutingRequest.mapTo[RoutingResponse]
    } {
      val itins2Cust = rideHailAgent2CustomerResponse.itineraries.filter(x => x.tripClassifier.equals(RIDE_HAIL))

      if (itins2Cust.nonEmpty) {
        val beamLegOverheadDuringInSeconds =
          ridehailManagerCustomizationAPI.beamLegOverheadDuringContinueRepositioningHook(vehicleId)

        val modRHA2Cust: IndexedSeq[EmbodiedBeamTrip] =
          itins2Cust
            .map(l =>
              l.copy(legs = l.legs.map(c => {
                val updatedDuration = c.beamLeg.duration + beamLegOverheadDuringInSeconds
                val updatedLeg = c.beamLeg.scaleToNewDuration(updatedDuration)
                c.copy(asDriver = true, beamLeg = updatedLeg)
              }))
            )
            .toIndexedSeq

        val rideHailAgent2CustomerResponseMod =
          RoutingResponse(
            modRHA2Cust,
            rideHailAgent2CustomerResponse.requestId,
            None,
            isEmbodyWithCurrentTravelTime = false,
            triggerId
          )

        ridehailManagerCustomizationAPI.processVehicleLocationUpdateAtEndOfContinueRepositioningHook(
          vehicleId,
          itins2Cust.head.legs.head.beamLeg.travelPath.endPoint.loc
        )

        val passengerSchedule = PassengerSchedule().addLegs(
          rideHailAgent2CustomerResponseMod.itineraries.head.toBeamTrip.legs
        )
        self ! RepositionVehicleRequest(
          passengerSchedule,
          tick,
          vehicleId,
          rideHailManagerHelper.getRideHailAgentLocation(vehicleId),
          triggerId
        )
      } else {
        self ! ReduceAwaitingRepositioningAckMessagesByOne(vehicleId, triggerId)
      }
    }
  }

  def getRideInitLocation(person: Person): Location = {
    val rideInitialLocation: Location =
      beamServices.beamConfig.beam.agentsim.agents.rideHail.initialization.procedural.initialLocation.name match {
        case RideHailManager.INITIAL_RIDE_HAIL_LOCATION_RANDOM_ACTIVITY =>
          val radius =
            beamServices.beamConfig.beam.agentsim.agents.rideHail.initialization.procedural.initialLocation.home.radiusInMeters
          val activityLocations: List[Location] =
            person.getSelectedPlan.getPlanElements.asScala
              .collect { case activity: Activity =>
                activity.getCoord
              }
              .toList
              .dropRight(1)
          val randomActivityLocation: Location = activityLocations(rand.nextInt(activityLocations.length))
          new Coord(
            randomActivityLocation.getX + radius * (rand.nextDouble() - 0.5),
            randomActivityLocation.getY + radius * (rand.nextDouble() - 0.5)
          )
        case RideHailManager.INITIAL_RIDE_HAIL_LOCATION_HOME =>
          val personInitialLocation: Location =
            person.getSelectedPlan.getPlanElements
              .iterator()
              .next()
              .asInstanceOf[Activity]
              .getCoord
          val radius =
            beamServices.beamConfig.beam.agentsim.agents.rideHail.initialization.procedural.initialLocation.home.radiusInMeters
          new Coord(
            personInitialLocation.getX + radius * (rand.nextDouble() - 0.5),
            personInitialLocation.getY + radius * (rand.nextDouble() - 0.5)
          )
        case RideHailManager.INITIAL_RIDE_HAIL_LOCATION_UNIFORM_RANDOM =>
          val x = activityQuadTreeBounds.minx + (activityQuadTreeBounds.maxx - activityQuadTreeBounds.minx) * rand
            .nextDouble()
          val y = activityQuadTreeBounds.miny + (activityQuadTreeBounds.maxy - activityQuadTreeBounds.miny) * rand
            .nextDouble()
          new Coord(x, y)
        case RideHailManager.INITIAL_RIDE_HAIL_LOCATION_ALL_AT_CENTER =>
          val x = activityQuadTreeBounds.minx + (activityQuadTreeBounds.maxx - activityQuadTreeBounds.minx) / 2
          val y = activityQuadTreeBounds.miny + (activityQuadTreeBounds.maxy - activityQuadTreeBounds.miny) / 2
          new Coord(x, y)
        case RideHailManager.INITIAL_RIDE_HAIL_LOCATION_ALL_IN_CORNER =>
          val x = activityQuadTreeBounds.minx
          val y = activityQuadTreeBounds.miny
          new Coord(x, y)
        case unknown =>
          log.error(s"unknown rideHail.initialLocation $unknown, assuming HOME")
          val personInitialLocation: Location =
            person.getSelectedPlan.getPlanElements
              .iterator()
              .next()
              .asInstanceOf[Activity]
              .getCoord
          val radius =
            beamServices.beamConfig.beam.agentsim.agents.rideHail.initialization.procedural.initialLocation.home.radiusInMeters
          new Coord(
            personInitialLocation.getX + radius * (rand.nextDouble() - 0.5),
            personInitialLocation.getY + radius * (rand.nextDouble() - 0.5)
          )
      }
    rideInitialLocation
  }

  /**
    * Check if the vehicle is still eligible to reposition. This filters out any circumstance where a vehicle that was
    * once selected for repositioning has since become unavailable due to the non-determinism of parallel discrete event
    * simulation.
    *
    * Returns true if the vehicle is still idle AND either the vehicle is not already allocated or is already on the way
    * to refuel.
    *
    * @param vehicleId
    * @return
    */
  def isEligibleToReposition(vehicleId: Id[BeamVehicle]): Boolean = {
    val serviceStatus = rideHailManagerHelper.getServiceStatusOf(vehicleId)
    val isNotAlreadyAllocated = !doNotUseInAllocation.contains(vehicleId)
    val isOnWayToRefuel = rideHailParkingNetwork.isOnWayToRefuelingDepot(vehicleId)
    (serviceStatus == Available || serviceStatus == Refueling) && (isNotAlreadyAllocated || isOnWayToRefuel)
  }
}
