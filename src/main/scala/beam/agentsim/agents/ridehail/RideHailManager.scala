package beam.agentsim.agents.ridehail

import java.awt.Color
import java.util
import java.util.Random
import java.util.concurrent.TimeUnit

import akka.actor.SupervisorStrategy.Stop
import akka.actor.{Actor, ActorLogging, ActorRef, OneForOneStrategy, Props, Stash, Terminated}
import akka.event.LoggingReceive
import akka.pattern._
import akka.util.Timeout
import beam.agentsim
import beam.agentsim.Resource._
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.InitializeTrigger
import beam.agentsim.agents.household.CAVSchedule.RouteOrEmbodyRequest
import beam.agentsim.agents.modalbehaviors.DrivesVehicle._
import beam.agentsim.agents.ridehail.RideHailAgent._
import beam.agentsim.agents.ridehail.RideHailManager._
import beam.agentsim.agents.ridehail.RideHailVehicleManager.RideHailAgentLocation
import beam.agentsim.agents.ridehail.allocation._
import beam.agentsim.agents.vehicles.AccessErrorCodes.{CouldNotFindRouteToCustomer, DriverNotFoundError, RideHailVehicleTakenError}
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.agents.vehicles.{PassengerSchedule, _}
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.ParkingManager.{DepotParkingInquiry, DepotParkingInquiryResponse}
import beam.agentsim.infrastructure.ParkingStall
import beam.agentsim.scheduler.BeamAgentScheduler.ScheduleTrigger
import beam.agentsim.scheduler.Trigger
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.analysis.plots.GraphsStatsAgentSimEventsListener
import beam.router.BeamRouter.{Location, RoutingRequest, RoutingResponse, _}
import beam.router.{BeamRouter, BeamSkimmer, RouteHistory}
import beam.router.Modes.BeamMode._
import beam.router.model.{BeamLeg, EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.router.osm.TollCalculator
import beam.sim.RideHailFleetInitializer.RideHailAgentInputData
import beam.sim._
import beam.utils._
import beam.utils.logging.LogActorState
import beam.utils.matsim_conversion.ShapeUtils.QuadTreeBounds
import beam.utils.reflection.ReflectionUtils
import com.conveyal.r5.transit.TransportNetwork
import com.eaio.uuid.UUIDGen
import com.google.common.cache.{Cache, CacheBuilder}
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.population.{Activity, Person, Population}
import org.matsim.api.core.v01.{Coord, Id, Scenario}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.vehicles.Vehicle

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
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

  sealed trait RideHailServiceStatus

  case object NotifyIterationEnds
  case class RecoverFromStuckness(tick: Int)

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
            rideHailAgentLocation.vehicleTypeId,
            false,
            estimatedPrice(passenger.personId),
            false,
            passengerSchedule.schedule.values.find(_.riders.size > 1).size > 0
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

  case class RepositionResponse(
    rnd1: RideHailAgentLocation,
    rnd2: RideHailAgentLocation,
    rnd1Response: RoutingResponse,
    rnd2Response: RoutingResponse
  )

  case class BufferedRideHailRequestsTrigger(tick: Int) extends Trigger

  case class RideHailRepositioningTrigger(tick: Int) extends Trigger

  case object DebugRideHailManagerDuringExecution

  case class ContinueBufferedRideHailRequests(tick: Int)

  final val fileBaseName = "rideHailInitialLocation"

  class OutputData extends OutputDataDescriptor {

    /**
      * Get description of fields written to the output files.
      *
      * @return list of data description objects
      */
    override def getOutputDataDescriptions: util.List[OutputDataDescription] = {
      val outputFilePath =
        GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(0, fileBaseName + ".csv")
      val outputDirPath = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getOutputPath
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

}

class RideHailManager(
  val id: Id[RideHailManager],
  val beamServices: BeamServices,
  val transportNetwork: TransportNetwork,
  val tollCalculator: TollCalculator,
  val scenario: Scenario,
  val eventsManager: EventsManager,
  val scheduler: ActorRef,
  val router: ActorRef,
  val parkingManager: ActorRef,
  val boundingBox: Envelope,
  val surgePricingManager: RideHailSurgePricingManager,
  val tncIterationStats: Option[TNCIterationStats],
  val beamSkimmer: BeamSkimmer,
  val routeHistory: RouteHistory
) extends Actor
    with ActorLogging
    with HasServices
    with Stash {

  implicit val timeout: Timeout = Timeout(50000, TimeUnit.SECONDS)
  override val supervisorStrategy: OneForOneStrategy =
    OneForOneStrategy(maxNrOfRetries = 0) {
      case e: Exception => {
        println(e)
        Stop
      }
      case _: AssertionError => Stop
    }

  private val vehicleType = beamServices.vehicleTypes
    .getOrElse(
      Id.create(
        beamServices.beamConfig.beam.agentsim.agents.rideHail.initialization.procedural.vehicleTypeId,
        classOf[BeamVehicleType]
      ),
      throw new RuntimeException(
        "Ride Hail vehicle type (param: beamServices.beamConfig.beam.agentsim.agents.rideHail.vehicleTypeId) could not be found"
      )
    )
  if (beamServices.beamConfig.beam.agentsim.agents.rideHail.refuelThresholdInMeters >= vehicleType.primaryFuelCapacityInJoule / vehicleType.primaryFuelConsumptionInJoulePerMeter * 0.8) {
    throw new RuntimeException(
      "Ride Hail refuel threshold is higher than state of energy of a vehicle fueled by a DC fast charger. This will cause an infinite loop"
    )
  }

  /**
    * Customer inquiries awaiting reservation confirmation.
    */
  val vehicleManager: RideHailVehicleManager = new RideHailVehicleManager(this, boundingBox)

  lazy val travelProposalCache: Cache[String, TravelProposal] = {
    CacheBuilder
      .newBuilder()
      .maximumSize(
        (10 * beamServices.beamConfig.beam.agentsim.agents.rideHail.initialization.procedural.numDriversAsFractionOfPopulation * beamServices.beamConfig.beam.agentsim.numAgents).toLong
      )
      .expireAfterWrite(1, TimeUnit.MINUTES)
      .build()
  }

  def fleetSize: Int = resources.size

  val radiusInMeters: Double =
    beamServices.beamConfig.beam.agentsim.agents.rideHail.rideHailManager.radiusInMeters

  val rideHailNetworkApi: RideHailNetworkAPI = new RideHailNetworkAPI()
  val processBufferedRequestsOnTimeout = beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.requestBufferTimeoutInSeconds > 0

  val quadTreeBounds: QuadTreeBounds = getQuadTreeBound(scenario.getPopulation)
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
  private val DefaultCostPerMinute = beamServices.beamConfig.beam.agentsim.agents.rideHail.defaultCostPerMinute
  tncIterationStats.foreach(_.logMap())
  private val DefaultCostPerSecond = DefaultCostPerMinute / 60.0d

  beamServices.beamRouter ! GetTravelTime
  beamServices.beamRouter ! GetMatSimNetwork
  //TODO improve search to take into account time when available
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

  private val numRideHailAgents = math.round(
    beamServices.beamConfig.beam.agentsim.numAgents.toDouble * beamServices.beamConfig.beam.agentsim.agents.rideHail.initialization.procedural.numDriversAsFractionOfPopulation
  )

  // Cache analysis
  private var cacheAttempts = 0
  private var cacheHits = 0

  private val rand = new Random(beamServices.beamConfig.matsim.modules.global.randomSeed)
  private val rideHailinitialLocationSpatialPlot = new SpatialPlot(1100, 1100, 50)
  val resources: mutable.Map[Id[BeamVehicle], BeamVehicle] = mutable.Map[Id[BeamVehicle], BeamVehicle]()

  beamServices.beamConfig.beam.agentsim.agents.rideHail.initialization.initType match {
    case "PROCEDURAL" =>
      val persons: Iterable[Person] = RandomUtils
        .shuffle(scenario.getPopulation.getPersons.values().asScala, rand)
        .take(numRideHailAgents.toInt)
      val fleetData: ArrayBuffer[RideHailFleetInitializer.RideHailAgentInputData] = new ArrayBuffer(persons.size)
      persons.foreach { person =>
        try {
          val rideInitialLocation: Location = getRideInitLocation(person)
          fleetData += createRideHailVehicleAndAgent(person.getId.toString, rideInitialLocation, None, None)
        } catch {
          case ex: Throwable =>
            log.error(ex, s"Could not createRideHailVehicleAndAgent: ${ex.getMessage}")
            throw ex
        }
      }

      new RideHailFleetInitializer().writeFleetData(beamServices, fleetData)

    case "FILE" =>
      new RideHailFleetInitializer().init(beamServices) foreach { fleetData =>
        createRideHailVehicleAndAgent(
          fleetData.id.split("-").toList.tail.mkString("-"),
          new Coord(fleetData.initialLocationX, fleetData.initialLocationY),
          fleetData.shifts,
          fleetData.toGeofence
        )
      }
    case _ =>
      log.error(
        "Unidentified initialization type : " +
        beamServices.beamConfig.beam.agentsim.agents.rideHail.initialization
      )
  }

  if (beamServices.matsimServices != null) {
    rideHailinitialLocationSpatialPlot.writeCSV(
      beamServices.matsimServices.getControlerIO
        .getIterationFilename(beamServices.iterationNumber, fileBaseName + ".csv")
    )

    if (beamServices.beamConfig.beam.outputs.writeGraphs) {
      rideHailinitialLocationSpatialPlot.writeImage(
        beamServices.matsimServices.getControlerIO
          .getIterationFilename(beamServices.iterationNumber, fileBaseName + ".png")
      )
    }
  }
  log.info("Initialized {} ride hailing agents", numRideHailAgents)

  def storeRoutes(responses: List[RoutingResponse]) = {
    responses.foreach { _.itineraries.view.foreach { resp =>
        resp.beamLegs().filter(_.mode == CAR).foreach { leg =>
          routeHistory.rememberRoute(leg.travelPath.linkIds, leg.startTime)
        }
      }
    }
  }

  override def receive: Receive = LoggingReceive {
    case LogActorState =>
      ReflectionUtils.logFields(log, this, 0)
      ReflectionUtils.logFields(log, rideHailResourceAllocationManager, 0)
      ReflectionUtils.logFields(log, modifyPassengerScheduleManager, 0)

    case RecoverFromStuckness(tick) =>
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
      modifyPassengerScheduleManager.sendCompletionAndScheduleNewTimeout(BatchedReservation, tick)
      rideHailResourceAllocationManager.clearPrimaryBufferAndFillFromSecondary
      cleanUp

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

    case Finish =>
      surgePricingManager.incrementIteration()
      context.children.foreach(_ ! Finish)
      dieIfNoChildren()
      context.become {
        case Terminated(_) =>
          dieIfNoChildren()
      }

    case NotifyVehicleOutOfService(vehicleId) =>
      vehicleManager.putOutOfService(vehicleManager.getRideHailAgentLocation(vehicleId))

    case ev @ NotifyVehicleIdle(
          vId,
          whenWhere,
          passengerSchedule,
          beamVehicleState,
          triggerId
        ) =>
      log.debug("RHM.NotifyVehicleResourceIdle: {}", ev)
      val vehicleId = vId.asInstanceOf[Id[Vehicle]]

      vehicleManager.updateLocationOfAgent(vehicleId, whenWhere, vehicleManager.getServiceStatusOf(vehicleId))

      val beamVehicle = resources(agentsim.vehicleId2BeamVehicleId(vehicleId))
      val rideHailAgentLocation =
        RideHailAgentLocation(beamVehicle.driver.get, vehicleId, beamVehicle.beamVehicleType.id, whenWhere)
      vehicleManager.vehicleState.put(vehicleId, beamVehicleState)

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
          resources(rideHailAgentLocation.vehicleId).useParkingStall(stallOpt.get)
          sender() ! NotifyVehicleResourceIdleReply(
            triggerId,
            Vector[ScheduleTrigger](startFuelTrigger)
          )
        } else if (beamVehicleState.remainingRangeInM < beamServices.beamConfig.beam.agentsim.agents.rideHail.refuelThresholdInMeters) {
          // not enough range to make trip

          if (modifyPassengerScheduleManager.vehicleHasMoreThanOneOngoingRequests(vehicleId)) {
            vehicleManager.putOutOfService(rideHailAgentLocation)
            sender() ! NotifyVehicleResourceIdleReply(triggerId, Vector[ScheduleTrigger]())
          } else {
            log.debug("Not enough range: {}", vehicleId)
            outOfServiceVehicleManager.registerTrigger(vehicleId, triggerId)
            vehicleManager.putOutOfService(rideHailAgentLocation)
            findRefuelStationAndSendVehicle(rideHailAgentLocation)
          }
        } else {
          log.debug("Making available: {}", vehicleId)
          vehicleManager.makeAvailable(rideHailAgentLocation)
          sender() ! NotifyVehicleResourceIdleReply(triggerId, Vector[ScheduleTrigger]())
        }
      } else {
        sender() ! NotifyVehicleResourceIdleReply(triggerId, Vector[ScheduleTrigger]())
      }
      modifyPassengerScheduleManager
        .checkInResource(vehicleId, Some(whenWhere), Some(passengerSchedule))

    case BeamVehicleStateUpdate(id, beamVehicleState) =>
      vehicleManager.vehicleState.put(id, beamVehicleState)

    case MATSimNetwork(network) =>
      rideHailNetworkApi.setMATSimNetwork(network)

    case inquiry @ RideHailRequest(RideHailInquiry, _, _, _, _, _, _, _) =>
      handleRideHailInquiry(inquiry)

    case R5Network(network) =>
      rideHailNetworkApi.setR5Network(network)

    /*
     * In the following case, we are calculating routes in batch for the allocation manager,
     * so we add these to the allocation buffer and then resume the allocation process.
     */
    case RoutingResponses(tick, responses)
        if reservationIdToRequest.contains(routeRequestIdToRideHailRequestId(responses.head.requestId)) =>
      storeRoutes(responses)
      numPendingRoutingRequestsForReservations = numPendingRoutingRequestsForReservations - responses.size
      responses.foreach { routeResponse =>
        val request = reservationIdToRequest(routeRequestIdToRideHailRequestId(routeResponse.requestId))
        rideHailResourceAllocationManager.addRouteForRequestToBuffer(request, routeResponse)
      }
      self ! ContinueBufferedRideHailRequests(tick)

    /*
     * Routing Responses from a Ride Hail Inquiry
     * In this case we can treat the responses as if they apply to a single request
     * for a single occupant trip.
     */
    case RoutingResponses(_, responses)
        if inquiryIdToInquiryAndResponse.contains(routeRequestIdToRideHailRequestId(responses.head.requestId)) =>
      val (request, singleOccupantQuoteAndPoolingInfo) = inquiryIdToInquiryAndResponse(
        routeRequestIdToRideHailRequestId(responses.head.requestId)
      )
      storeRoutes(responses)

      // If any response contains no RIDE_HAIL legs, then the router failed
      if (responses.exists(!_.itineraries.exists(_.tripClassifier.equals(RIDE_HAIL)))) {
        log.debug(
          "Router could not find route to customer person={} for requestId={}",
          request.customer.personId,
          request.requestId
        )
        request.customer.personRef ! RideHailResponse(
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

        val baseFare = embodiedBeamTrip.legs.map(_.cost).sum

        val travelProposal = TravelProposal(
          singleOccupantQuoteAndPoolingInfo.rideHailAgentLocation,
          driverPassengerSchedule,
          calcFare(request, driverPassengerSchedule, baseFare),
          singleOccupantQuoteAndPoolingInfo.poolingInfo
        )
        travelProposalCache.put(request.requestId.toString, travelProposal)

        request.customer.personRef ! RideHailResponse(request, Some(travelProposal))
      }
      inquiryIdToInquiryAndResponse.remove(request.requestId)
      responses.foreach(rResp => routeRequestIdToRideHailRequestId.remove(rResp.requestId))

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
          log.debug("Stashing BufferedRideHailRequestsTrigger({})", tick)
          stash()
        case None =>
          currentlyProcessingTimeoutTrigger = Some(trigger)
          log.debug("Starting wave of buffered at {}", tick)
          modifyPassengerScheduleManager.startWaveOfRepositioningOrBatchedReservationRequests(tick, triggerId)
          findAllocationsAndProcess(tick)
      }

    case ContinueBufferedRideHailRequests(tick) =>
      // If modifyPassengerScheduleManager holds a tick, we're in buffered mode
      modifyPassengerScheduleManager.getCurrentTick match {
        case Some(workingTick) =>
          log.debug(
            "ContinueBuffer @ {} with buffer size {}",
            workingTick,
            rideHailResourceAllocationManager.getBufferSize
          )
          if (workingTick != tick) log.warning("Working tick {} but tick {}", workingTick, tick)
          findAllocationsAndProcess(workingTick)
        case None if !processBufferedRequestsOnTimeout =>
          // this case is how we process non-buffered requests
          findAllocationsAndProcess(tick)
        case _ =>
          log.error("Should not make it here")
      }

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
      if (vehicleManager.getIdleVehicles.contains(vehicleId)) {
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

    case msg =>
      log.warning("unknown message received by RideHailManager {}", msg)

  }

  def dieIfNoChildren(): Unit = {
    log.info("Route Request Cache hits ({} / {}) or {}%",cacheHits,cacheAttempts,Math.round(cacheHits.toDouble/cacheAttempts.toDouble*100))
    if (context.children.isEmpty) {
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
        inquiry.customer.personRef ! RideHailResponse(inquiry, None, Some(DriverNotFoundError))
      case inquiryResponse @ SingleOccupantQuoteAndPoolingInfo(agentLocation, poolingInfo) =>
        inquiryIdToInquiryAndResponse.put(inquiry.requestId, (inquiry, inquiryResponse))
        val routingRequests = createRoutingRequestsToCustomerAndDestination(inquiry.departAt, inquiry, agentLocation)
        routingRequests.foreach(rReq => routeRequestIdToRideHailRequestId.put(rReq.requestId, inquiry.requestId))
        requestRoutes(inquiry.departAt, routingRequests)
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
      rideHailLocation.vehicleTypeId,
      SpaceTime((rideHailLocation.currentLocationUTM.loc, requestTime)),
      CAR,
      asDriver = false
    )
    val rideHailVehicleAtPickup =
      StreetVehicle(rideHailLocation.vehicleId, rideHailLocation.vehicleTypeId, pickupSpaceTime, CAR, asDriver = false)

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
    cacheAttempts = cacheAttempts + 1
    val routeOrEmbodyReqs = routingRequests.map{ rReq =>
    routeHistory.getRoute(beamServices.geo.getNearestR5EdgeToUTMCoord(transportNetwork.streetLayer,rReq.originUTM),
      beamServices.geo.getNearestR5EdgeToUTMCoord(transportNetwork.streetLayer,rReq.destinationUTM), rReq.departureTime) match {
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
          Some(rReq.requestId)
        )
        RouteOrEmbodyRequest(None, Some(embodyReq))
      case None =>
        RouteOrEmbodyRequest(Some(rReq), None)
      }
    }
    Future
      .sequence(routeOrEmbodyReqs.map(req => akka.pattern.ask(router, if (req.routeReq.isDefined) { req.routeReq.get } else { req.embodyReq.get }).mapTo[RoutingResponse]))
      .map(RoutingResponses(tick, _)) pipeTo self
  }

  private def handleReservation(request: RideHailRequest, tick: Int, travelProposal: TravelProposal): Unit = {
    surgePricingManager.addRideCost(
      request.departAt,
      travelProposal.estimatedPrice(request.customer.personId),
      request.pickUpLocationUTM
    )

    // This makes the vehicle unavailable for others to reserve
    vehicleManager.putIntoService(travelProposal.rideHailAgentLocation)

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
      "Removing request: {} pendingAcks: {} pendingRoutes: {} requestBufferSize: {}",
      requestId,
      s"(${pendingModifyPassengerScheduleAcks.size}) ${pendingModifyPassengerScheduleAcks.keySet.map(_.toString).mkString(",")}",
      numPendingRoutingRequestsForReservations,
      rideHailResourceAllocationManager.getBufferSize
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
          response.request.customer.personRef ! response.copy(triggersToSchedule = Vector())
          response.request.groupedWithOtherRequests.foreach { subReq =>
            subReq.customer.personRef ! response.copy(triggersToSchedule = Vector())
          }
        } else {
          response.request.customer.personRef ! response.copy(
            triggersToSchedule = finalTriggersToSchedule
          )
        }
        // The following is an API call to allow implementing class to process or cleanup
        rideHailResourceAllocationManager.reservationCompletionNotice(response.request.customer.personId, theVehicle)
      case None =>
        log.error("Vehicle was reserved by another agent for inquiry id {}", requestId)
        sender() ! RideHailResponse.dummyWithError(RideHailVehicleTakenError)
    }
    if (processBufferedRequestsOnTimeout && currentlyProcessingTimeoutTrigger.isDefined) {
      if (pendingModifyPassengerScheduleAcks.isEmpty) {
        rideHailResourceAllocationManager.clearPrimaryBufferAndFillFromSecondary
        modifyPassengerScheduleManager.sendCompletionAndScheduleNewTimeout(BatchedReservation, tick)
        cleanUp
      }
    }
  }

  private def handleReservationRequest(request: RideHailRequest): Unit = {
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
      // We always use the request buffer even if we will process these requests immediately
      rideHailResourceAllocationManager.addRequestToBuffer(request)
      findAllocationsAndProcess(request.departAt)
    }
  }

  def createRideHailVehicleAndAgent(
    rideHailAgentIdentifier: String,
    rideInitialLocation: Coord,
    shifts: Option[String],
    geofence: Option[Geofence]
  ): RideHailAgentInputData = {
    val rideHailAgentName = s"rideHailAgent-${rideHailAgentIdentifier}"
    val rideHailVehicleId = BeamVehicle.createId(rideHailAgentIdentifier, Some("rideHailVehicle"))
    val ridehailBeamVehicleTypeId =
      Id.create(
        beamServices.beamConfig.beam.agentsim.agents.rideHail.initialization.procedural.vehicleTypeId,
        classOf[BeamVehicleType]
      )
    val ridehailBeamVehicleType = beamServices.vehicleTypes
      .getOrElse(ridehailBeamVehicleTypeId, BeamVehicleType.defaultCarBeamVehicleType)
    val rideHailAgentPersonId: Id[RideHailAgent] =
      Id.create(rideHailAgentName, classOf[RideHailAgent])
    val powertrain = Option(ridehailBeamVehicleType.primaryFuelConsumptionInJoulePerMeter)
      .map(new Powertrain(_))
      .getOrElse(Powertrain.PowertrainFromMilesPerGallon(Powertrain.AverageMilesPerGallon))
    val rideHailBeamVehicle = new BeamVehicle(
      rideHailVehicleId,
      powertrain,
      ridehailBeamVehicleType
    )
    rideHailBeamVehicle.spaceTime = SpaceTime((rideInitialLocation, 0))
    rideHailBeamVehicle.manager = Some(self)
    resources += (rideHailVehicleId -> rideHailBeamVehicle)
    vehicleManager.vehicleState.put(rideHailBeamVehicle.id, rideHailBeamVehicle.getState)

    val rideHailAgentProps: Props = RideHailAgent.props(
      beamServices,
      scheduler,
      transportNetwork,
      tollCalculator,
      eventsManager,
      parkingManager,
      rideHailAgentPersonId,
      self,
      rideHailBeamVehicle,
      rideInitialLocation,
      shifts.map(_.split(";").map(beam.sim.common.Range(_)).toList),
      geofence
    )

    val rideHailAgentRef: ActorRef =
      context.actorOf(rideHailAgentProps, rideHailAgentName)
    context.watch(rideHailAgentRef)
    scheduler ! ScheduleTrigger(InitializeTrigger(0), rideHailAgentRef)

    val agentLocation = RideHailAgentLocation(
      rideHailAgentRef,
      rideHailBeamVehicle.id,
      ridehailBeamVehicleTypeId,
      SpaceTime(rideInitialLocation, 0)
    )
    // Put the agent outWriter of service and let the agent tell us when it's Idle (aka ready for service)
    vehicleManager.putOutOfService(agentLocation)

    rideHailinitialLocationSpatialPlot
      .addString(StringToPlot(s"${rideHailAgentIdentifier}", rideInitialLocation, Color.RED, 20))
    rideHailinitialLocationSpatialPlot
      .addAgentWithCoord(
        RideHailAgentInitCoord(rideHailAgentPersonId, rideInitialLocation)
      )
    RideHailAgentInputData(
      id = rideHailBeamVehicle.id.toString,
      rideHailManagerId = id.toString,
      vehicleType = rideHailBeamVehicle.beamVehicleType.id.toString,
      initialLocationX = rideInitialLocation.getX,
      initialLocationY = rideInitialLocation.getY,
      shifts = shifts,
      geofenceX = geofence.map(fence => fence.geofenceX),
      geofenceY = geofence.map(fence => fence.geofenceY),
      geofenceRadius = geofence.map(fence => fence.geofenceRadius)
    )
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
                rReq => routeRequestIdToRideHailRequestId.put(rReq.requestId, request.requestId)
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
      log.debug("sendCompletionAndScheduleNewTimeout for tick {} from line 1072", tick)
      modifyPassengerScheduleManager.sendCompletionAndScheduleNewTimeout(BatchedReservation, tick)
      rideHailResourceAllocationManager.clearPrimaryBufferAndFillFromSecondary
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
      pickDrops.map(_.personId).zip(BeamLeg.makeLegsConsistent(pickDrops.map(_.leg.map(_.beamLeg))))
    val allLegs = consistentPickDrops.map(_._2).flatten
    var passSched = PassengerSchedule().addLegs(allLegs)
    var pickDropsForGrouping: Map[VehiclePersonId, List[BeamLeg]] = Map()
    var passengersToAdd = Set[VehiclePersonId]()
    consistentPickDrops.foreach {
      case (Some(person), legOpt) =>
        legOpt.foreach { leg =>
          passengersToAdd.foreach { pass =>
            val legsForPerson = pickDropsForGrouping.get(pass).getOrElse(List()) :+ leg
            pickDropsForGrouping = pickDropsForGrouping + (pass -> legsForPerson)
          }
        }
        if (passengersToAdd.contains(person)) {
          passengersToAdd = passengersToAdd - person
        } else {
          passengersToAdd = passengersToAdd + person
        }
      case (_, _) =>
    }
    pickDropsForGrouping.foreach { passAndLegs =>
      passSched = passSched.addPassenger(passAndLegs._1, passAndLegs._2)
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
    } else {
      request.customer.personRef ! theResponse
    }
    rideHailResourceAllocationManager.removeRequestFromBuffer(request)
  }

  def getQuadTreeBound(population: Population): QuadTreeBounds = {
    val persons = population.getPersons.values().asInstanceOf[util.Collection[Person]].asScala.view
    val activities = persons.flatMap(p => p.getSelectedPlan.getPlanElements.asScala.view).collect {
      case activity: Activity =>
        activity
    }
    val coordinates = activities.map(_.getCoord)
    // Force to compute xs and ys arrays
    val xs = coordinates.map(_.getX).toArray
    val ys = coordinates.map(_.getY).toArray
    val xMin = xs.min
    val xMax = xs.max
    val yMin = ys.min
    val yMax = ys.max
    log.info(
      s"QuadTreeBounds with X: [$xMin; $xMax], Y: [$yMin, $yMax]. boundingBoxBuffer: ${beamServices.beamConfig.beam.spatial.boundingBoxBuffer}"
    )
    QuadTreeBounds(
      xMin - beamServices.beamConfig.beam.spatial.boundingBoxBuffer,
      yMin - beamServices.beamConfig.beam.spatial.boundingBoxBuffer,
      xMax + beamServices.beamConfig.beam.spatial.boundingBoxBuffer,
      yMax + beamServices.beamConfig.beam.spatial.boundingBoxBuffer
    )
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
      if (vehicleManager.getIdleVehicles.contains(vehicleId)) {
        val rideHailAgentLocation = vehicleManager.getIdleVehicles(vehicleId)

        val rideHailVehicleAtOrigin = StreetVehicle(
          rideHailAgentLocation.vehicleId,
          rideHailAgentLocation.vehicleTypeId,
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
            val rideHailAgent2CustomerResponseMod = RoutingResponse(modRHA2Cust, routingRequest.requestId)

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

  def getRideInitLocation(person: Person): Location = {
    val personInitialLocation: Location =
      person.getSelectedPlan.getPlanElements
        .iterator()
        .next()
        .asInstanceOf[Activity]
        .getCoord
    val rideInitialLocation: Location =
      beamServices.beamConfig.beam.agentsim.agents.rideHail.initialization.procedural.initialLocation.name match {
        case RideHailManager.INITIAL_RIDE_HAIL_LOCATION_HOME =>
          val radius =
            beamServices.beamConfig.beam.agentsim.agents.rideHail.initialization.procedural.initialLocation.home.radiusInMeters
          new Coord(
            personInitialLocation.getX + radius * (rand.nextDouble() - 0.5),
            personInitialLocation.getY + radius * (rand.nextDouble() - 0.5)
          )
        case RideHailManager.INITIAL_RIDE_HAIL_LOCATION_UNIFORM_RANDOM =>
          val x = quadTreeBounds.minx + (quadTreeBounds.maxx - quadTreeBounds.minx) * rand
            .nextDouble()
          val y = quadTreeBounds.miny + (quadTreeBounds.maxy - quadTreeBounds.miny) * rand
            .nextDouble()
          new Coord(x, y)
        case RideHailManager.INITIAL_RIDE_HAIL_LOCATION_ALL_AT_CENTER =>
          val x = quadTreeBounds.minx + (quadTreeBounds.maxx - quadTreeBounds.minx) / 2
          val y = quadTreeBounds.miny + (quadTreeBounds.maxy - quadTreeBounds.miny) / 2
          new Coord(x, y)
        case RideHailManager.INITIAL_RIDE_HAIL_LOCATION_ALL_IN_CORNER =>
          val x = quadTreeBounds.minx
          val y = quadTreeBounds.miny
          new Coord(x, y)
        case unknown =>
          log.error(s"unknown rideHail.initialLocation $unknown, assuming HOME")
          val radius =
            beamServices.beamConfig.beam.agentsim.agents.rideHail.initialization.procedural.initialLocation.home.radiusInMeters
          new Coord(
            personInitialLocation.getX + radius * (rand.nextDouble() - 0.5),
            personInitialLocation.getY + radius * (rand.nextDouble() - 0.5)
          )
      }
    rideInitialLocation
  }

}
