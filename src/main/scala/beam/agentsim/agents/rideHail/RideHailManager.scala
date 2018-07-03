package beam.agentsim.agents.rideHail

import java.time.{ZoneOffset, ZonedDateTime}
import java.util
import java.util.concurrent.TimeUnit

import akka.actor.{ActorLogging, ActorRef, Props}
import akka.pattern._
import akka.util.Timeout
import beam.agentsim
import beam.agentsim.Resource._
import beam.agentsim.ResourceManager.VehicleManager
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.PersonAgent
import beam.agentsim.agents.household.HouseholdActor.ReleaseVehicleReservation
import beam.agentsim.agents.modalBehaviors.DrivesVehicle.{BeamVehicleFuelLevelUpdate, GetBeamVehicleFuelLevel, StopDriving}
import beam.agentsim.agents.rideHail.RideHailAgent._
import beam.agentsim.agents.rideHail.RideHailManager._
import beam.agentsim.agents.rideHail.allocationManagers._
import beam.agentsim.agents.vehicles.AccessErrorCodes.{CouldNotFindRouteToCustomer, RideHailVehicleTakenError, UnknownInquiryIdError, UnknownRideHailReservationError}
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.agents.vehicles.{PassengerSchedule, _}
import beam.agentsim.events.SpaceTime
import beam.agentsim.events.resources.ReservationError
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.{Trigger, TriggerWithId}
import beam.analysis.plots.RideHailRevenueAnalysis
import beam.router.BeamRouter._
import beam.router.BeamRouter.{Location, RoutingRequest, RoutingResponse}
import beam.router.Modes.BeamMode._
import beam.router.RoutingModel
import beam.router.RoutingModel.{BeamLeg, BeamTime, BeamTrip, DiscreteTime}
import beam.sim.{BeamServices, HasServices}
import beam.utils.{RoutingRequestResponseStats, RoutingRequestSenderCounter}
import beam.utils.DebugLib
import com.conveyal.r5.profile.{ProfileRequest, StreetMode}
import com.conveyal.r5.transit.TransportNetwork
import com.eaio.uuid.UUIDGen
import com.google.common.cache.{Cache, CacheBuilder}
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.network.{Link, Network}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.network.NetworkUtils
import org.matsim.core.router.util.TravelTime
import org.matsim.core.utils.collections.QuadTree
import org.matsim.core.utils.geometry.CoordUtils
import org.matsim.vehicles.Vehicle

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer
import scala.collection.{concurrent, mutable}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.concurrent.duration.{Duration, FiniteDuration}

// TODO: RW: We need to update the location of vehicle as it is moving to give good estimate to ride hail allocation manager
// TODO: Build RHM from XML to be able to specify different kinds of TNC/Rideshare types and attributes
// TODO: remove name variable, as not used currently in the code anywhere?

class RideHailManager(
                          val beamServices: BeamServices,
                          val scheduler: ActorRef,
                          val router: ActorRef,
                          val boundingBox: Envelope,
                          val surgePricingManager: RideHailSurgePricingManager)
  extends VehicleManager with ActorLogging with HasServices {

  override val resources: mutable.Map[Id[BeamVehicle], BeamVehicle] = mutable.Map[Id[BeamVehicle], BeamVehicle]()

  private val vehicleFuelLevel: mutable.Map[Id[Vehicle], Double] = mutable.Map[Id[Vehicle], Double]()

  private val DefaultCostPerMinute = BigDecimal(beamServices.beamConfig.beam.agentsim.agents.rideHail.defaultCostPerMinute)
  private val DefaultCostPerSecond = DefaultCostPerMinute / 60.0d

  private val rideHailAllocationManagerTimeoutInSeconds = {
    beamServices.beamConfig.beam.agentsim.agents.rideHail.rideHailAllocationManagerTimeoutInSeconds
  }

  val allocationManager: String = beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager

  val rideHailResourceAllocationManager: RideHailResourceAllocationManager = allocationManager match {
    case RideHailResourceAllocationManager.DEFAULT_MANAGER =>
      new DefaultRideHailResourceAllocationManager()
    case RideHailResourceAllocationManager.STANFORD_V1 =>
      new StanfordRideHailAllocationManagerV1(this)
    case RideHailResourceAllocationManager.BUFFERED_IMPL_TEMPLATE =>
      new RideHailAllocationManagerBufferedImplTemplate(this)
    case RideHailResourceAllocationManager.REPOSITIONING_LOW_WAITING_TIMES =>
      new RepositioningWithLowWaitingTimes(this)
    case _ =>
      new DefaultRideHailResourceAllocationManager()
  }

  // TODO: remove following (replace by RideHailModifyPassengerScheduleManager)
  private val repositioningPassengerSchedule: mutable.Map[Id[Vehicle], (Id[Interrupt], Option[PassengerSchedule])] = mutable.Map[Id[Vehicle], (Id[Interrupt],Option[PassengerSchedule])]()
  private val reservationPassengerSchedule: mutable.Map[Id[Vehicle], (Id[Interrupt], ModifyPassengerSchedule)] =mutable.Map[Id[Vehicle], (Id[Interrupt],ModifyPassengerSchedule)]()
  private val repositioningVehicles: mutable.Set[Id[Vehicle]] = mutable.Set[Id[Vehicle]]() // TODO: move to RideHailModifyPassengerScheduleManager?

  val modifyPassengerScheduleManager = new RideHailModifyPassengerScheduleManager(log,self,rideHailAllocationManagerTimeoutInSeconds,scheduler)


  private val repositionDoneOnce: Boolean = false

  private var maybeTravelTime: Option[TravelTime] = None

  private var bufferedReserveRideMessages = mutable.Map[Id[RideHailInquiry], ReserveRide]()

  private val handleRideHailInquirySubmitted = mutable.Set[Id[RideHailInquiry]]()

  var nextCompleteNoticeRideHailAllocationTimeout: CompletionNotice = _

  beamServices.beamRouter ! GetTravelTime
  beamServices.beamRouter ! GetMatSimNetwork

  var transportNetwork: Option[TransportNetwork] = None
  var matsimNetwork: Option[Network] = None


  //TODO improve search to take into account time when available
  private val availableRideHailAgentSpatialIndex = {
    new QuadTree[RideHailAgentLocation](
      boundingBox.getMinX,
      boundingBox.getMinY,
      boundingBox.getMaxX,
      boundingBox.getMaxY)
  }

  private val inServiceRideHailAgentSpatialIndex = {
    new QuadTree[RideHailAgentLocation](
      boundingBox.getMinX,
      boundingBox.getMinY,
      boundingBox.getMaxX,
      boundingBox.getMaxY)
  }
  private val availableRideHailVehicles = concurrent.TrieMap[Id[Vehicle], RideHailAgentLocation]()
  private val inServiceRideHailVehicles = concurrent.TrieMap[Id[Vehicle], RideHailAgentLocation]()

  /**
    * Customer inquiries awaiting reservation confirmation.
    */
  lazy val pendingInquiries: Cache[Id[RideHailInquiry], (TravelProposal, BeamTrip)] = {
    CacheBuilder.newBuilder()
      .expireAfterWrite(10, TimeUnit.SECONDS)
      .build()
  }

  private val pendingModifyPassengerScheduleAcks = collection.concurrent.TrieMap[Id[RideHailInquiry],
    ReservationResponse]()
  private val lockedVehicles = mutable.HashSet[Id[Vehicle]]()

  //val tickTask = context.system.scheduler.schedule(2.seconds, 10.seconds, self, "tick")(scala.concurrent.ExecutionContext.Implicits.global)


  override def receive: Receive = {
    case "tick" =>
      log.info("rideHailingManager Full time (ms): {}",
        RoutingRequestResponseStats.fullTimeStat)
      log.info("rideHailingManager RoutingRequest travel time (ms): {}",
        RoutingRequestResponseStats.requestTravelTimeStat)
      log.info("rideHailingManager RoutingResponse travel time (ms): {}",
        RoutingRequestResponseStats.responseTravelTimeStat)
      log.info("rideHailingManager Route calc time (ms): {}",
        RoutingRequestResponseStats.routeCalcTime)
      log.info(s"Sending ${RoutingRequestSenderCounter.rate} per seconds of RoutingRequest")
    case NotifyIterationEnds() =>
      surgePricingManager.incrementIteration()

      sender ! Unit // return empty object to blocking caller

    case RegisterResource(vehId: Id[Vehicle]) =>
      resources.put(agentsim.vehicleId2BeamVehicleId(vehId), beamServices.vehicles(vehId))

    case NotifyResourceIdle(vehId: Id[Vehicle], whenWhere) =>
      updateLocationOfAgent(vehId, whenWhere, isAvailable = true)

      resources.get(agentsim.vehicleId2BeamVehicleId(vehId))
        .flatMap(_.driver)
        .foreach { driverActor =>
          driverActor ! GetBeamVehicleFuelLevel
        }

    case NotifyResourceInUse(vehId: Id[Vehicle], whenWhere) =>
      updateLocationOfAgent(vehId, whenWhere, isAvailable = false)

    case CheckInResource(vehicleId: Id[Vehicle], availableIn: Option[SpaceTime]) =>
      resources.get(agentsim.vehicleId2BeamVehicleId(vehicleId)).orElse(beamServices.vehicles.get(vehicleId)).get.driver.foreach(driver => {
        val rideHailAgentLocation = RideHailAgentLocation(driver, vehicleId, availableIn.get)
        if (modifyPassengerScheduleManager.containsPendingReservations(vehicleId)) {
          // we still might have some ongoing resrvation in going on
          makeAvailable(rideHailAgentLocation)
        }
        sender ! CheckInSuccess
        repositioningVehicles.remove(vehicleId)
        log.debug("checking in resource: vehicleId(" + vehicleId + ");availableIn.time(" + availableIn.get.time + ")")
        modifyPassengerScheduleManager.checkInResource(vehicleId, availableIn)
      })


    case BeamVehicleFuelLevelUpdate(id, fuelLevel) =>
      vehicleFuelLevel.put(id, fuelLevel)

    case Finish =>
      log.info("received Finish message")

    case MATSimNetwork(network) =>
      matsimNetwork = Some(network)

    case CheckOutResource(_) =>
      // Because the RideHail Manager is in charge of deciding which specific vehicles to assign to customers, this should never be used
      throw new RuntimeException("Illegal use of CheckOutResource, RideHailManager is responsible for checking out vehicles in fleet.")


    case inquiry@RideHailInquiry(inquiryId, personId, customerPickUp, departAt, destination) =>
      val customerAgent = sender()
      var rideHailLocationAndShortDistance: Option[(RideHailAgentLocation, Double)] = None

      val vehicleAllocationRequest = VehicleAllocationRequest(customerPickUp, departAt, destination, isInquiry = true)

      val vehicleAllocation = rideHailResourceAllocationManager.proposeVehicleAllocation(vehicleAllocationRequest)

      vehicleAllocation match {
        case Some(allocation) =>
          // TODO (RW): Test following code with stanford class
          val rideHailAgent = resources.get(agentsim.vehicleId2BeamVehicleId(allocation.vehicleId)).orElse(beamServices.vehicles.get(allocation.vehicleId)).get.driver.head
          val rideHailAgentLocation = RideHailAgentLocation(rideHailAgent, allocation.vehicleId, allocation.availableAt)
          val distance = CoordUtils.calcProjectedEuclideanDistance(customerPickUp, rideHailAgentLocation.currentLocation.loc)
          rideHailLocationAndShortDistance = Some(rideHailAgentLocation, distance)


        case None =>
          // use default allocation manager
          rideHailLocationAndShortDistance = getClosestIdleRideHailAgent(customerPickUp, radiusInMeters)
      }

      handleRideHailInquiry(inquiryId, personId, customerPickUp, departAt, destination, rideHailLocationAndShortDistance, Some(customerAgent))

    case R5Network(network) =>
      this.transportNetwork = Some(network)

    case RoutingResponses(customerAgent,
    inquiryId,
    personId,
    customerPickUp,
    departAt,
    rideHailLocation,
    shortDistanceToRideHailAgent,
    rideHailAgent2CustomerResponse,
    rideHail2DestinationResponse) =>
      val timesToCustomer: Seq[Long] = rideHailAgent2CustomerResponse.itineraries.map(t => t.totalTravelTimeInSecs)

      // TODO: Find better way of doing this error checking than sentry value
      val timeToCustomer = if (timesToCustomer.isEmpty) Long.MaxValue else timesToCustomer.min

      // TODO: Do unit conversion elsewhere... use squants or homegrown unit conversions, but enforce
      val rideHailFarePerSecond = DefaultCostPerSecond * surgePricingManager.getSurgeLevel(customerPickUp, departAt.atTime.toDouble)

      val customerPlans2Costs: Map[RoutingModel.EmbodiedBeamTrip, BigDecimal] = rideHail2DestinationResponse.itineraries.map(t => (t, rideHailFarePerSecond * t.totalTravelTimeInSecs)).toMap
      val itins2Cust = rideHailAgent2CustomerResponse.itineraries.filter(x => x.tripClassifier.equals(RIDE_HAIL))
      val itins2Dest = rideHail2DestinationResponse.itineraries.filter(x => x.tripClassifier.equals(RIDE_HAIL))
      if (timeToCustomer < Long.MaxValue && customerPlans2Costs.nonEmpty && itins2Cust.nonEmpty && itins2Dest.nonEmpty) {
        val (customerTripPlan, cost) = customerPlans2Costs.minBy(_._2)

        //TODO: include customerTrip plan in response to reuse( as option BeamTrip can include createdTime to check if the trip plan is still valid
        //TODO: we response with collection of TravelCost to be able to consolidate responses from different ride hailing companies

        val modRHA2Cust = itins2Cust.map(l => l.copy(legs = l.legs.map(c => c.copy(asDriver = true))))
        val modRHA2Dest = itins2Dest.map(l => l.copy(legs = l.legs.zipWithIndex.map(c => c._1.copy(asDriver = c._1.beamLeg.mode == WALK,
          unbecomeDriverOnCompletion = c._2 == 2,
          beamLeg = c._1.beamLeg.copy(startTime = c._1.beamLeg.startTime + timeToCustomer),
          cost = if (c._1.beamLeg == l.legs(1).beamLeg) {
            cost
          } else {
            0.0
          }
        ))))

        val rideHailAgent2CustomerResponseMod = RoutingResponse(modRHA2Cust, requestCreatedAt = rideHailAgent2CustomerResponse.requestCreatedAt,
          requestReceivedAt = rideHailAgent2CustomerResponse.requestReceivedAt, createdAt = rideHailAgent2CustomerResponse.createdAt,
          receivedAt = rideHailAgent2CustomerResponse.receivedAt, id = rideHailAgent2CustomerResponse.id, requestId = rideHailAgent2CustomerResponse.requestId)
        val rideHail2DestinationResponseMod = RoutingResponse(modRHA2Dest, requestCreatedAt = rideHail2DestinationResponse.requestCreatedAt,
          requestReceivedAt = rideHail2DestinationResponse.requestReceivedAt, createdAt = rideHail2DestinationResponse.createdAt,
          receivedAt = rideHail2DestinationResponse.receivedAt, id = rideHailAgent2CustomerResponse.id, requestId = rideHail2DestinationResponse.requestId)

        val travelProposal = TravelProposal(rideHailLocation, timeToCustomer, cost, Option(FiniteDuration
        (customerTripPlan.totalTravelTimeInSecs, TimeUnit.SECONDS)), rideHailAgent2CustomerResponseMod,
          rideHail2DestinationResponseMod)
        pendingInquiries.put(inquiryId, (travelProposal, modRHA2Dest.head.toBeamTrip()))
        log.debug(s"Found ride to hail for  person=$personId and inquiryId=$inquiryId within " +
          s"$shortDistanceToRideHailAgent meters, timeToCustomer=$timeToCustomer seconds and cost=$$$cost")


        customerAgent match {
          case Some(customerActor) => customerActor ! RideHailInquiryResponse(inquiryId, Vector(travelProposal))
          case None =>

            bufferedReserveRideMessages.get(inquiryId) match {
              case Some(ReserveRide(localInquiryId, vehiclePersonIds, localCustomerPickUp, localDepartAt, destination)) =>
                handlePendingQuery(localInquiryId, vehiclePersonIds, localCustomerPickUp, localDepartAt, destination)
                bufferedReserveRideMessages.remove(localInquiryId)
                handleRideHailInquirySubmitted.remove(localInquiryId)

                // TODO if there is any issue related to bufferedReserveRideMessages, look at this closer (e.g. make two data structures, one for those
                // before timout and one after
                if (handleRideHailInquirySubmitted.isEmpty) {
                  modifyPassengerScheduleManager.sendoutAckMessageToSchedulerForRideHailAllocationmanagerTimeout()
                }

              case _ =>
            }
          // call was made by ride hail agent itself


        }

      } else {
        log.debug(s"Router could not find route to customer person=$personId for inquiryId=$inquiryId")
        lockedVehicles -= rideHailLocation.vehicleId


        customerAgent match {
          case Some(customerAgent) => customerAgent ! RideHailInquiryResponse(inquiryId, Vector(), error = Option(CouldNotFindRouteToCustomer))
          case None => // TODO RW handle this
        }
      }

    case reserveRide@ReserveRide(inquiryId, vehiclePersonIds, customerPickUp, departAt, destination) =>
      if (rideHailResourceAllocationManager.isBufferedRideHailAllocationMode) {
        bufferedReserveRideMessages += (inquiryId -> reserveRide)
        //System.out.println("")
      } else {
        handlePendingQuery(inquiryId, vehiclePersonIds, customerPickUp, departAt, destination)
      }


    case modifyPassengerScheduleAck@ModifyPassengerScheduleAck(inquiryIDOption, triggersToSchedule, vehicleId) =>
      log.debug("modifyPassengerScheduleAck received: " + modifyPassengerScheduleAck)
      if (inquiryIDOption.isEmpty) {
        modifyPassengerScheduleManager.modifyPassengerScheduleAckReceivedForRepositioning(triggersToSchedule)

        // val newTriggers = triggersToSchedule ++ nextCompleteNoticeRideHailAllocationTimeout.newTriggers
        // scheduler ! CompletionNotice(nextCompleteNoticeRideHailAllocationTimeout.id, newTriggers)
      } else {
        completeReservation(Id.create(inquiryIDOption.get.toString, classOf[RideHailInquiry]), triggersToSchedule)
      }

    case ReleaseVehicleReservation(_, vehId) =>
      lockedVehicles -= vehId

    // TODO (RW): this needs to be called according to timeout settings

    case UpdateTravelTime(travelTime) =>
      maybeTravelTime = Some(travelTime)

    case DebugRideHailManagerDuringExecution =>
      modifyPassengerScheduleManager.printState()


    case TriggerWithId(RideHailAllocationManagerTimeout(tick), triggerId) => {

      modifyPassengerScheduleManager.startWaiveOfRepositioningRequests(tick, triggerId)

      if (repositionDoneOnce) {
        modifyPassengerScheduleManager.sendoutAckMessageToSchedulerForRideHailAllocationmanagerTimeout()
      } else {
        val repositionVehicles: Vector[(Id[Vehicle], Location)] = rideHailResourceAllocationManager.repositionVehicles(tick)

        if (repositionVehicles.isEmpty) {
          modifyPassengerScheduleManager.sendoutAckMessageToSchedulerForRideHailAllocationmanagerTimeout()
        } else {
          modifyPassengerScheduleManager.setNumberOfRepositioningsToProcess(repositionVehicles.size)
        }

        var sentRequestCount = 0
        for (repositionVehicle <- repositionVehicles) {

          val (vehicleId, destinationLocation) = repositionVehicle

          if (getIdleVehicles().contains(vehicleId)) {
            val rideHailAgentLocation = getIdleVehicles()(vehicleId)

            // println("RHM: tick(" + tick + ")" + vehicleId + " - " + rideHailAgentLocation.currentLocation.loc + " -> " + destinationLocation)

            val rideHailAgent = rideHailAgentLocation.rideHailAgent

            val rideHailVehicleAtOrigin = StreetVehicle(rideHailAgentLocation.vehicleId, SpaceTime(
              (rideHailAgentLocation.currentLocation.loc, tick.toLong)), CAR, asDriver = false)

            val departAt = DiscreteTime(tick.toInt)

            implicit val timeout: Timeout = Timeout(50000, TimeUnit.SECONDS)

            val routingRequest = RoutingRequest(
              origin = rideHailAgentLocation.currentLocation.loc,
              destination = destinationLocation,
              departureTime = departAt,
              transitModes = Vector(),
              streetVehicles = Vector(rideHailVehicleAtOrigin),
              createdAt = ZonedDateTime.now(ZoneOffset.UTC)
            ) //TODO what should go in vectors
            val futureRideHailAgent2CustomerResponse = router ? routingRequest

            sentRequestCount = sentRequestCount + 1
            RoutingRequestSenderCounter.sent()

            for {
              rideHailAgent2CustomerResponse <- futureRideHailAgent2CustomerResponse.mapTo[RoutingResponse]
                                                                                          .map{_.copy(receivedAt = Some(ZonedDateTime.now(ZoneOffset.UTC)))}
            } {
              val itins2Cust = rideHailAgent2CustomerResponse.itineraries.filter(x => x.tripClassifier.equals(RIDE_HAIL))

              if (itins2Cust.nonEmpty) {
                val modRHA2Cust: Seq[RoutingModel.EmbodiedBeamTrip] = itins2Cust.map(l => l.copy(legs = l.legs.map(c => c.copy(asDriver = true))))
                val rideHailAgent2CustomerResponseMod = RoutingResponse(modRHA2Cust, requestCreatedAt = rideHailAgent2CustomerResponse.requestCreatedAt,
                  requestReceivedAt = rideHailAgent2CustomerResponse.requestReceivedAt, createdAt = rideHailAgent2CustomerResponse.createdAt,
                  receivedAt = rideHailAgent2CustomerResponse.receivedAt, id = rideHailAgent2CustomerResponse.id, requestId = rideHailAgent2CustomerResponse.requestId)

                // TODO: extract creation of route to separate method?
                val passengerSchedule = PassengerSchedule().addLegs(rideHailAgent2CustomerResponseMod.itineraries.head.toBeamTrip.legs)

                self ! RepositionVehicleRequest(passengerSchedule,tick,vehicleId,rideHailAgent)

              } else {
                self ! ReduceAwaitingRepositioningAckMessagesByOne
                //println("NO repositioning done")
              }
            }

          } else {
            modifyPassengerScheduleManager.modifyPassengerScheduleAckReceivedForRepositioning(Vector())
          }
        }


      }

      //log.debug("RepositioningTimeout("+tick +") - END repositioning waive")
      //modifyPassengerScheduleManager.endWaiveOfRepositioningRequests()



      /*
          if (rideHailResourceAllocationManager.isBufferedRideHailAllocationMode && bufferedReserveRideMessages.values.size>0){
            var map: Map[Id[RideHailInquiry], VehicleAllocationRequest] = Map[Id[RideHailInquiry], VehicleAllocationRequest]()
           bufferedReserveRideMessages.values.foreach{ reserveRide =>
             map += (reserveRide.inquiryId -> VehicleAllocationRequest( reserveRide.pickUpLocation,reserveRide.departAt,reserveRide.destination))
            }
            var resultMap =rideHailResourceAllocationManager .allocateVehiclesInBatch(map)
            for (ReserveRide(inquiryId, vehiclePersonIds, customerPickUp, departAt, destination) <- bufferedReserveRideMessages.values) {
              resultMap(inquiryId).vehicleAllocation match {
                case Some(vehicleAllocation) =>
                  if (!handleRideHailInquirySubmitted.contains(inquiryId)){
                    val rideHailAgent=resources.get(agentsim.vehicleId2BeamVehicleId(vehicleAllocation.vehicleId)).orElse(beamServices.vehicles.get(vehicleAllocation.vehicleId)).get.driver.head
                    val rideHailAgentLocation=RideHailingAgentLocation(rideHailAgent, vehicleAllocation.vehicleId, vehicleAllocation.availableAt)
                    val distance=CoordUtils.calcProjectedEuclideanDistance(customerPickUp,rideHailAgentLocation.currentLocation.loc)
                    val rideHailLocationAndShortDistance = Some(rideHailAgentLocation,distance)
                    handleRideHailInquirySubmitted += inquiryId
                    handleRideHailInquiry(inquiryId, vehiclePersonIds.personId, customerPickUp, departAt, destination,rideHailLocationAndShortDistance,None)
                  }
                case None =>
                // TODO: how to handle case, if no car availalbe????? -> check
              }
            }
              // TODO (RW) to make the following call compatible with default code, we need to probably manipulate
          //
            } else {
            sendoutAckMessageToSchedulerForRideHailAllocationmanagerTimeout()
            }
      */
      // TODO (RW) add repositioning code here


      // beamServices.schedulerRef ! TriggerUtils.completed(triggerId,Vector(timerMessage))


    }

    case ReduceAwaitingRepositioningAckMessagesByOne =>
      modifyPassengerScheduleManager.modifyPassengerScheduleAckReceivedForRepositioning(Vector())

    case RepositionVehicleRequest(passengerSchedule,tick,vehicleId,rideHailAgent) =>


      repositioningVehicles.add(vehicleId)

      // TODO: send following to a new case, which handles it
      // -> code for sending message could be prepared in modifyPassengerScheduleManager
      // e.g. create case class
      log.debug("RideHailAllocationManagerTimeout: requesting to send interrupt message to vehicle for repositioning: " + vehicleId )
      modifyPassengerScheduleManager.repositionVehicle(passengerSchedule,tick,vehicleId,rideHailAgent)
    //repositioningPassengerSchedule.put(vehicleId,(rideHailAgentInterruptId, Some(passengerSchedule)))



    case InterruptedWhileIdle(interruptId,vehicleId,tick) =>
      modifyPassengerScheduleManager.handleInterrupt(InterruptedWhileIdle.getClass,interruptId,None,vehicleId,tick)

    case InterruptedAt(interruptId,interruptedPassengerSchedule, currentPassengerScheduleIndex,vehicleId,tick) =>
      modifyPassengerScheduleManager.handleInterrupt(InterruptedAt.getClass,interruptId,Some(interruptedPassengerSchedule),vehicleId,tick)

    case Finish =>
      log.info("finish message received from BeamAgentScheduler")

    case msg =>
      log.warning(s"unknown message received by RideHailManager $msg")

  }

  // TODO: move to modifyPassengerScheduleManager?
  private def handleInterrupt( interruptType:String, interruptId: Id[Interrupt],interruptedPassengerSchedule: Option[PassengerSchedule],vehicleId:Id[Vehicle], tick: Double): Unit ={
    log.debug(interruptType + " - vehicle: " + vehicleId)
    val rideHailAgent =getRideHailAgent(vehicleId)
    if (repositioningPassengerSchedule.contains(vehicleId)){
      val (interruptIdReposition, passengerSchedule)=repositioningPassengerSchedule(vehicleId)
      if (reservationPassengerSchedule.contains(vehicleId)){
        val (interruptIdReservation, modifyPassengerSchedule)=reservationPassengerSchedule(vehicleId)
        interruptedPassengerSchedule.foreach(interruptedPassengerSchedule => updateIdleVehicleLocation(vehicleId,interruptedPassengerSchedule.schedule.head._1,tick))
        log.debug(interruptType + " - ignoring reposition: " + vehicleId)
      } else {
        interruptedPassengerSchedule.foreach(_ => rideHailAgent ! StopDriving())
        rideHailAgent ! ModifyPassengerSchedule(passengerSchedule.get)
        rideHailAgent ! Resume()
        log.debug(interruptType + " - reposition: " + vehicleId)
      }
    }

    if (reservationPassengerSchedule.contains(vehicleId)) {
      val (interruptIdReservation, modifyPassengerSchedule) = reservationPassengerSchedule(vehicleId)
      if (interruptId == interruptIdReservation) {
        val (interruptIdReservation, modifyPassengerSchedule) = reservationPassengerSchedule.remove(vehicleId).get
        interruptedPassengerSchedule.foreach(_ => rideHailAgent ! StopDriving())
        rideHailAgent ! modifyPassengerSchedule
        rideHailAgent ! Resume()
        log.debug(interruptType + " - reservation: " + vehicleId)
      } else {
        log.error(interruptType + " - reservation: " + vehicleId + " interruptId doesn't match (interruptId,interruptIdReservation):" + interruptId + "," + interruptIdReservation)
      }
    }
  }



  private def getRideHailAgent(vehicleId:Id[Vehicle]):ActorRef={
    getRideHailAgentLocation(vehicleId).rideHailAgent
  }

  private def getRideHailAgentLocation(vehicleId:Id[Vehicle]):RideHailAgentLocation={
    getIdleVehicles().getOrElse(vehicleId,inServiceRideHailVehicles(vehicleId))
  }


  private def isVehicleRepositioning(vehicleId:Id[Vehicle]):Boolean={
    repositioningPassengerSchedule.contains(vehicleId)
  }

  // contains both idling and repositioning vehicles
  private def getNotInUseVehicleLocation(vehicleId:Id[Vehicle],tick:Double): RideHailAgentLocation ={
    if (isVehicleRepositioning(vehicleId)){
      updateIdleVehicleLocation(vehicleId, repositioningPassengerSchedule(vehicleId)._2.get.schedule.head._1,tick)
    }

    getIdleVehicles()(vehicleId)
  }



  private def updateIdleVehicleLocation(vehicleId:Id[Vehicle],beamLeg:BeamLeg,tick:Double): Unit ={
    val vehicleCoord=getVehicleCoordinate(beamLeg,tick)

    val rideHailAgentLocation=getRideHailAgentLocation(vehicleId)

    getIdleVehicles().put(vehicleId,rideHailAgentLocation.copy(currentLocation = SpaceTime(vehicleCoord,tick.toLong)))
  }




  // private def sendoutAckMessageToSchedulerForRideHailAllocationmanagerTimeout(): Unit = {
  //   scheduler ! nextCompleteNoticeRideHailAllocationTimeout
  // }

  private def findClosestRideHailAgents(inquiryId: Id[RideHailInquiry], customerPickUp: Location) = {

    val travelPlanOpt = Option(pendingInquiries.asMap.get(inquiryId))
    val customerAgent = sender()
    /**
      * 1. customerAgent ! ReserveRideConfirmation(availableRideHailAgentSpatialIndex, customerId, travelProposal)
      * 2. availableRideHailAgentSpatialIndex ! PickupCustomer
      */
    val nearbyRideHailAgents = availableRideHailAgentSpatialIndex.getDisk(customerPickUp.getX, customerPickUp.getY,
      radiusInMeters).asScala.toVector
    val closestRHA: Option[RideHailAgentLocation] = nearbyRideHailAgents.filter(x =>
      lockedVehicles(x.vehicleId)).find(_.vehicleId.equals(travelPlanOpt.get._1.responseRideHail2Pickup
      .itineraries.head.vehiclesInTrip.head))
    (travelPlanOpt, customerAgent, closestRHA)
  }

  private def createCustomerInquiryResponse(personId: Id[PersonAgent],
                                            customerPickUp: Location,
                                            departAt: BeamTime,
                                            destination: Location,
                                            rideHailLocation: RideHailAgentLocation): (Future[Any], Future[Any]) = {
    val customerAgentBody = StreetVehicle(Id.createVehicleId(s"body-$personId"), SpaceTime((customerPickUp,
      departAt.atTime)), WALK, asDriver = true)
    val rideHailVehicleAtOrigin = StreetVehicle(rideHailLocation.vehicleId, SpaceTime(
      (rideHailLocation.currentLocation.loc, departAt.atTime)), CAR, asDriver = false)
    val rideHailVehicleAtPickup = StreetVehicle(rideHailLocation.vehicleId, SpaceTime((customerPickUp,
      departAt.atTime)), CAR, asDriver = false)

    //TODO: Error handling. In the (unlikely) event of a timeout, this RideHailManager will silently be
    //TODO: restarted, and probably someone will wait forever for its reply.
    implicit val timeout: Timeout = Timeout(50000, TimeUnit.SECONDS)

    // get route from ride hailing vehicle to customer
    val futureRideHailAgent2CustomerResponse = router ? RoutingRequest(rideHailLocation
      .currentLocation.loc, customerPickUp, departAt, Vector(), Vector(rideHailVehicleAtOrigin), createdAt = ZonedDateTime.now(ZoneOffset.UTC))
    RoutingRequestSenderCounter.sent()
    //XXXX: customer trip request might be redundant... possibly pass in info

    // get route from customer to destination
    val futureRideHail2DestinationResponse = router ? RoutingRequest(customerPickUp, destination, departAt, Vector(), Vector(customerAgentBody, rideHailVehicleAtPickup), createdAt = ZonedDateTime.now(ZoneOffset.UTC))
    RoutingRequestSenderCounter.sent()
    (futureRideHailAgent2CustomerResponse, futureRideHail2DestinationResponse)
  }


  private def updateLocationOfAgent(vehicleId: Id[Vehicle], whenWhere: SpaceTime, isAvailable: Boolean) = {
    if (isAvailable) {
      availableRideHailVehicles.get(vehicleId) match {
        case Some(prevLocation) =>
          val newLocation = prevLocation.copy(currentLocation = whenWhere)
          availableRideHailAgentSpatialIndex.remove(prevLocation.currentLocation.loc.getX, prevLocation.currentLocation.loc.getY, prevLocation)
          availableRideHailAgentSpatialIndex.put(newLocation.currentLocation.loc.getX, newLocation.currentLocation.loc.getY, newLocation)
          availableRideHailVehicles.put(newLocation.vehicleId, newLocation)
        case None =>
      }
    } else {
      inServiceRideHailVehicles.get(vehicleId) match {
        case Some(prevLocation) =>
          val newLocation = prevLocation.copy(currentLocation = whenWhere)
          inServiceRideHailAgentSpatialIndex.remove(prevLocation.currentLocation.loc.getX, prevLocation.currentLocation.loc.getY, prevLocation)
          inServiceRideHailAgentSpatialIndex.put(newLocation.currentLocation.loc.getX, newLocation.currentLocation.loc.getY, newLocation)
          inServiceRideHailVehicles.put(newLocation.vehicleId, newLocation)
        case None =>
      }
    }
  }

  def getTravelTimeEstimate(time: Double, linkId: Int): Double = {
    maybeTravelTime match {
      case Some(matsimTravelTime) =>
        matsimTravelTime.getLinkTravelTime(matsimNetwork.get.getLinks.get(Id.createLinkId(linkId)), time, null, null).toLong
      case None =>
        val edge = transportNetwork.get.streetLayer.edgeStore.getCursor(linkId)
        (edge.getLengthM / edge.calculateSpeed(new ProfileRequest, StreetMode.valueOf(StreetMode.CAR.toString))).toLong
    }
  }

  def getClosestLink(coord: Coord): Option[Link] = {
    matsimNetwork match {
      case Some(network) => Some(NetworkUtils.getNearestLink(network, coord));
      case None => None
    }
  }

  def getFreeFlowTravelTime(linkId: Int): Option[Double] = {
    getLinks() match {
      case Some(links) => Some(links.get(Id.createLinkId(linkId.toString)).getFreespeed)
      case None => None
    }
  }


  def getFromLinkIds(linkId: Int): Vector[Int] = {
    convertLinkIdsToVector(getMATSimLink(linkId).getFromNode.getInLinks.keySet()) // Id[Link].toString
  }

  def getToLinkIds(linkId: Int): Vector[Int] = {
    convertLinkIdsToVector(getMATSimLink(linkId).getToNode.getOutLinks.keySet()) // Id[Link].toString
  }

  def convertLinkIdsToVector(set: util.Set[Id[Link]]): Vector[Int] = {
    set.asScala.map { linkId => linkId.toString.toInt }.toVector
  }

  private def getVehicleCoordinate(beamLeg:BeamLeg, stopTime:Double): Coord ={




    // TODO: implement following solution following along links
    /*
    var currentTime=beamLeg.startTime
     var resultCoord=beamLeg.travelPath.endPoint.loc
    if (stopTime<beamLeg.endTime) {
      for (linkId <- beamLeg.travelPath.linkIds) {
        val linkEndTime=currentTime + getTravelTimeEstimate(currentTime, linkId)
        breakable {
          if (stopTime < linkEndTime) {
              resultCoord=getLinkCoord(linkId)
            break
          }
        }
      }
    }
    */

    val pctTravelled=(stopTime-beamLeg.startTime)/(beamLeg.endTime-beamLeg.startTime)
    val directionCoordVector=getDirectionCoordVector(beamLeg.travelPath.startPoint.loc,beamLeg.travelPath.endPoint.loc)
    getCoord(beamLeg.travelPath.startPoint.loc,scaleDirectionVector(directionCoordVector,pctTravelled))
  }

  // TODO: move to some utility class,   e.g. geo
  private def getDirectionCoordVector(startCoord:Coord, endCoord:Coord): Coord ={
    new Coord(endCoord.getX()-startCoord.getX(),endCoord.getY()-startCoord.getY())
  }

  private def getCoord(startCoord:Coord,directionCoordVector:Coord): Coord ={
    new Coord(startCoord.getX()+directionCoordVector.getX(),startCoord.getY()+directionCoordVector.getY())
  }

  private def scaleDirectionVector(directionCoordVector:Coord, scalingFactor:Double):Coord={
    new Coord(directionCoordVector.getX()*scalingFactor,directionCoordVector.getY()*scalingFactor)
  }

  private def getMATSimLink(linkId: Int): Link = {
    matsimNetwork.get.getLinks.get(Id.createLinkId(linkId))
  }

  def getLinkCoord(linkId: Int): Coord = {
    matsimNetwork.get.getLinks.get(Id.createLinkId(linkId)).getCoord
  }

  def getFromNodeCoordinate(linkId: Int): Coord = {
    matsimNetwork.get.getLinks.get(Id.createLinkId(linkId)).getFromNode.getCoord
  }

  def getToNodeCoordinate(linkId: Int): Coord = {
    matsimNetwork.get.getLinks.get(Id.createLinkId(linkId)).getToNode.getCoord
  }


  // TODO: make integers
  def getLinks(): Option[util.Map[Id[Link], _ <: Link]] = {
    matsimNetwork match {
      case Some(network) => Some(network.getLinks)
      case None => None
    }
  }

  private def makeAvailable(agentLocation: RideHailAgentLocation) = {
    availableRideHailVehicles.put(agentLocation.vehicleId, agentLocation)
    availableRideHailAgentSpatialIndex.put(agentLocation.currentLocation.loc.getX,
      agentLocation.currentLocation.loc.getY, agentLocation)
    inServiceRideHailVehicles.remove(agentLocation.vehicleId)
    inServiceRideHailAgentSpatialIndex.remove(agentLocation.currentLocation.loc.getX,
      agentLocation.currentLocation.loc.getY, agentLocation)
  }

  private def putIntoService(agentLocation: RideHailAgentLocation) = {
    availableRideHailVehicles.remove(agentLocation.vehicleId)
    availableRideHailAgentSpatialIndex.remove(agentLocation.currentLocation.loc.getX,
      agentLocation.currentLocation.loc.getY, agentLocation)
    inServiceRideHailVehicles.put(agentLocation.vehicleId, agentLocation)
    inServiceRideHailAgentSpatialIndex.put(agentLocation.currentLocation.loc.getX,
      agentLocation.currentLocation.loc.getY, agentLocation)
  }

  private def handleReservation(inquiryId: Id[RideHailInquiry], vehiclePersonId: VehiclePersonId,
                                customerPickUp: Location, destination: Location,
                                customerAgent: ActorRef, closestRideHailAgentLocation: RideHailAgentLocation,
                                travelProposal: TravelProposal, trip2DestPlan: Option[BeamTrip]): Unit = {

    // Modify RH agent passenger schedule and create BeamAgentScheduler message that will dispatch RH agent to do the
    // pickup
    val passengerSchedule = PassengerSchedule()
      .addLegs(travelProposal.responseRideHail2Pickup.itineraries.head.toBeamTrip.legs) // Adds empty trip to customer
      .addPassenger(vehiclePersonId, trip2DestPlan.get.legs.filter(_.mode == CAR)) // Adds customer's actual trip to destination
    putIntoService(closestRideHailAgentLocation)
    lockedVehicles -= closestRideHailAgentLocation.vehicleId

    // Create confirmation info but stash until we receive ModifyPassengerScheduleAck
    pendingModifyPassengerScheduleAcks.put(inquiryId, ReservationResponse(Id.create(inquiryId.toString,
      classOf[ReservationRequest]), Right(ReserveConfirmInfo(trip2DestPlan.head.legs.head, trip2DestPlan.last.legs
      .last, vehiclePersonId, Vector()))))

    //val rideHailAgentInterruptId = nextRideHailAgentInterruptId

    //closestRideHailAgentLocation.rideHailAgent ! Interrupt(rideHailAgentInterruptId,passengerSchedule.schedule.head._1.startTime)
    // RideHailAgent sends reply which we are ignoring here,
    // but that's okay, we don't _need_ to wait for the reply if the answer doesn't interest us.



    //reservationPassengerSchedule.put(closestRideHailAgentLocation.vehicleId,(rideHailAgentInterruptId, ModifyPassengerSchedule(passengerSchedule, Some(inquiryId))))

    log.debug("reserving vehicle: " + closestRideHailAgentLocation.vehicleId )
    modifyPassengerScheduleManager.reserveVehicle(passengerSchedule,passengerSchedule.schedule.head._1.startTime,closestRideHailAgentLocation.vehicleId,closestRideHailAgentLocation.rideHailAgent,Some(inquiryId))

    // modifyPassengerScheduleManager.add(new RideHailModifyPassengerScheduleStatus(rideHailAgentInterruptId,closestRideHailAgentLocation.vehicleId,passengerSchedule,InterruptOrigin.RESERVATION))


    //closestRideHailAgentLocation.rideHailAgent ! ModifyPassengerSchedule(passengerSchedule, Some(inquiryId))
    //closestRideHailAgentLocation.rideHailAgent ! Resume()

  }



  private def completeReservation(inquiryId: Id[RideHailInquiry], triggersToSchedule: Seq[ScheduleTrigger]): Unit = {
    pendingModifyPassengerScheduleAcks.remove(inquiryId) match {
      case Some(response) =>
        log.debug(s"Completed reservation for $inquiryId")
        val customerRef = beamServices.personRefs(response.response.right.get.passengerVehiclePersonId.personId)
        customerRef ! response.copy(response = Right(response.response.right.get.copy(triggersToSchedule = triggersToSchedule.toVector)))
      case None =>
        log.error(s"Vehicle was reserved by another agent for inquiry id $inquiryId")
        sender() ! ReservationResponse(Id.create(inquiryId.toString, classOf[ReservationRequest]), Left
        (RideHailVehicleTakenError))
    }

  }


  def getClosestIdleVehiclesWithinRadius(pickupLocation: Coord, radius: Double): Seq[(RideHailAgentLocation,
    Double)] = {
    getIdleVehiclesWithinRadius(pickupLocation, radius).sortBy(_._2)
  }

  def getIdleVehiclesWithinRadius(pickupLocation: Coord, radius: Double): Seq[(RideHailAgentLocation,
    Double)] = {
    val nearbyRideHailAgents = availableRideHailAgentSpatialIndex.getDisk(pickupLocation.getX, pickupLocation.getY,
      radius).asScala.toVector
    val distances2RideHailAgents = nearbyRideHailAgents.map(rideHailAgentLocation => {
      val distance = CoordUtils.calcProjectedEuclideanDistance(pickupLocation, rideHailAgentLocation
        .currentLocation.loc)
      (rideHailAgentLocation, distance)
    })
    //TODO: Possibly get multiple taxis in this block
    distances2RideHailAgents.filterNot(x => lockedVehicles(x._1.vehicleId))
  }

  def getClosestIdleRideHailAgent(pickupLocation: Coord,
                                  radius: Double): Option[(RideHailAgentLocation, Double)] = {
    val idleVehicles = getIdleVehiclesWithinRadius(pickupLocation, radius)
    if (idleVehicles.isEmpty) None
    else {
      Some(idleVehicles.minBy { case (location, radius) => radius })
    }
  }


  private def handlePendingQuery(inquiryId: Id[RideHailInquiry], vehiclePersonIds: VehiclePersonId, customerPickUp: Location,
                                 departAt: BeamTime, destination: Location): Unit = {
    if (pendingInquiries.asMap.containsKey(inquiryId)) {
      val (travelPlanOpt: Option[(TravelProposal, BeamTrip)], customerAgent: ActorRef, closestRHA: Option[RideHailAgentLocation]) = findClosestRideHailAgents(inquiryId, customerPickUp)

      closestRHA match {
        case Some(closestRideHailAgent) =>
          val travelProposal = travelPlanOpt.get._1
          surgePricingManager.addRideCost(departAt.atTime, travelProposal.estimatedPrice.doubleValue(), customerPickUp)


          val tripPlan = travelPlanOpt.map(_._2)
          handleReservation(inquiryId, vehiclePersonIds, customerPickUp, destination, customerAgent,
            closestRideHailAgent, travelProposal, tripPlan)
        // We have an agent nearby, but it's not the one we originally wanted
        case _ =>
          customerAgent ! ReservationResponse(Id.create(inquiryId.toString, classOf[ReservationRequest]), Left
          (UnknownRideHailReservationError))
      }
    } else {
      sender() ! ReservationResponse(Id.create(inquiryId.toString, classOf[ReservationRequest]), Left
      (UnknownInquiryIdError))
    }
  }

  def lockVehicle(vehicleId: Id[Vehicle]): Unit = {
    lockedVehicles += vehicleId
  }


  def getVehicleFuelLevel(vehicleId: Id[Vehicle]): Double = vehicleFuelLevel(vehicleId)

  def getIdleVehicles(): collection.concurrent.TrieMap[Id[Vehicle], RideHailAgentLocation] = {
    availableRideHailVehicles
  }


  private def handleRideHailInquiry(inquiryId: Id[RideHailInquiry], personId: Id[PersonAgent],
                                    customerPickUp: Location, departAt: BeamTime, destination: Location, rideHailLocationAndShortDistance: Option[(RideHailAgentLocation, Double)], customerAgent: Option[ActorRef]): Unit = {
    rideHailLocationAndShortDistance match {
      case Some((rideHailLocation, shortDistanceToRideHailAgent)) =>
        if (!rideHailResourceAllocationManager.isBufferedRideHailAllocationMode) {
          // only lock vehicle in immediate processing mode, in buffered processing mode we lock vehicle only when batch queries are beeing processing
          lockedVehicles += rideHailLocation.vehicleId
        }

        // Need to have this dispatcher here for the future execution below

        val (futureRideHailAgent2CustomerResponse, futureRideHail2DestinationResponse) =
          createCustomerInquiryResponse(personId, customerPickUp, departAt, destination, rideHailLocation)

        for {
          rideHailAgent2CustomerResponse <- futureRideHailAgent2CustomerResponse.mapTo[RoutingResponse]
                                                                                      .map{_.copy(receivedAt = Some(ZonedDateTime.now(ZoneOffset.UTC)))}
          rideHail2DestinationResponse <- futureRideHail2DestinationResponse.mapTo[RoutingResponse]
                                                                                  .map{_.copy(receivedAt = Some(ZonedDateTime.now(ZoneOffset.UTC)))}
        } {
          RoutingRequestResponseStats.add(rideHailAgent2CustomerResponse)
          RoutingRequestResponseStats.add(rideHail2DestinationResponse)
          // TODO: could we just call the code, instead of sending the message here?
          self ! RoutingResponses(customerAgent, inquiryId, personId, customerPickUp, departAt, rideHailLocation, shortDistanceToRideHailAgent, rideHailAgent2CustomerResponse, rideHail2DestinationResponse)
        }
      case None =>
        // no rides to hail
        customerAgent match {
          case Some(localCustomerAgent) =>
            localCustomerAgent ! RideHailInquiryResponse(inquiryId, Vector(), error = Option(CouldNotFindRouteToCustomer))
          case None =>
            self ! RideHailInquiryResponse(inquiryId, Vector(), error = Option(CouldNotFindRouteToCustomer))
          // TODO RW: handle this error case where RideHailInquiryResponse is sent back to us
        }
    }
  }


}



object RideHailManager {
  val radiusInMeters: Double = 5000d

  val INITIAL_RIDEHAIL_LOCATION_HOME = "HOME"
  val INITIAL_RIDEHAIL_LOCATION_UNIFORM_RANDOM = "UNIFORM_RANDOM"
  val INITIAL_RIDEHAIL_LOCATION_ALL_AT_CENTER = "ALL_AT_CENTER"

  def nextRideHailInquiryId: Id[RideHailInquiry] = {
    Id.create(UUIDGen.createTime(UUIDGen.newTime()).toString, classOf[RideHailInquiry])
  }



  case class NotifyIterationEnds()

  case class RideHailInquiry(inquiryId: Id[RideHailInquiry],
                             customerId: Id[PersonAgent],
                             pickUpLocation: Location,
                             departAt: BeamTime,
                             destination: Location)

  case class TravelProposal(rideHailAgentLocation: RideHailAgentLocation,
                            timesToCustomer: Long,
                            estimatedPrice: BigDecimal,
                            estimatedTravelTime: Option[Duration],
                            responseRideHail2Pickup: RoutingResponse,
                            responseRideHail2Dest: RoutingResponse)

  case class RideHailInquiryResponse(inquiryId: Id[RideHailInquiry],
                                     proposals: Seq[TravelProposal],
                                     error: Option[ReservationError] = None)

  case class ReserveRide(inquiryId: Id[RideHailInquiry],
                         customerIds: VehiclePersonId,
                         pickUpLocation: Location,
                         departAt: BeamTime,
                         destination: Location)

  private case class RoutingResponses(customerAgent: Option[ActorRef],
                                      inquiryId: Id[RideHailInquiry],
                                      personId: Id[PersonAgent],
                                      customerPickUp: Location,
                                      departAt: BeamTime,
                                      rideHailLocation: RideHailAgentLocation,
                                      shortDistanceToRideHailAgent: Double,
                                      rideHailAgent2CustomerResponse: RoutingResponse,
                                      rideHail2DestinationResponse: RoutingResponse)

  case class ReserveRideResponse(inquiryId: Id[RideHailInquiry], data: Either[ReservationError, RideHailConfirmData])

  case class RideHailConfirmData(rideHailAgent: ActorRef, customerId: Id[PersonAgent], travelProposal: TravelProposal)

  case class RegisterRideAvailable(rideHailAgent: ActorRef, vehicleId: Id[Vehicle], availableSince: SpaceTime)

  case class RegisterRideUnavailable(ref: ActorRef, location: Coord)

  case class RideHailAgentLocation(rideHailAgent: ActorRef, vehicleId: Id[Vehicle], currentLocation: SpaceTime)

  case object RideUnavailableAck

  case object RideAvailableAck

  case object DebugRideHailManagerDuringExecution

  case class RepositionResponse(rnd1: RideHailAgentLocation,
                                rnd2: RideHailManager.RideHailAgentLocation,
                                rnd1Response: RoutingResponse,
                                rnd2Response: RoutingResponse)

  case class RideHailAllocationManagerTimeout(tick: Double) extends Trigger


  def props(services: BeamServices,
            scheduler: ActorRef,
            router: ActorRef,
            boundingBox: Envelope,
            surgePricingManager:
            RideHailSurgePricingManager): Props = {
    Props(new RideHailManager(services, scheduler, router, boundingBox, surgePricingManager))
  }
}