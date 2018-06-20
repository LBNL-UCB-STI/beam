package beam.agentsim.agents.rideHail

import java.awt.Color
import java.util.concurrent.TimeUnit

import akka.actor.{ActorLogging, ActorRef, Props}
import akka.pattern._
import akka.util.Timeout
import beam.agentsim
import beam.agentsim.Resource._
import beam.agentsim.ResourceManager.VehicleManager
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.PersonAgent
import beam.agentsim.agents.modalBehaviors.DrivesVehicle.{BeamVehicleFuelLevelUpdate, GetBeamVehicleFuelLevel}
import beam.agentsim.agents.rideHail.RideHailIterationHistoryActor.GetCurrentIterationRideHailStats
import beam.agentsim.agents.rideHail.RideHailingAgent._
import beam.agentsim.agents.rideHail.RideHailingManager.{ReserveRide, _}
import beam.agentsim.agents.rideHail.allocationManagers._
import beam.agentsim.agents.vehicles.AccessErrorCodes.{CouldNotFindRouteToCustomer, RideHailVehicleTakenError}
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.agents.vehicles.{PassengerSchedule, _}
import beam.agentsim.events.SpaceTime
import beam.agentsim.events.resources.ReservationError
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.{Trigger, TriggerWithId}
import beam.router.BeamRouter.{Location, RoutingRequest, RoutingResponse, _}
import beam.router.Modes.BeamMode._
import beam.router.RoutingModel
import beam.router.RoutingModel.{BeamTime, DiscreteTime}
import beam.sim.{BeamServices, HasServices}
import beam.utils.{DebugLib, PointToPlot, SpatialPlot}
import com.eaio.uuid.UUIDGen
import com.google.common.cache.{Cache, CacheBuilder}
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.collections.QuadTree
import org.matsim.core.utils.geometry.CoordUtils
import org.matsim.vehicles.Vehicle

import scala.collection.JavaConverters._
import scala.collection.{concurrent, mutable}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.concurrent.{Await, Future}

// TODO: RW: We need to update the location of vehicle as it is moving to give good estimate to ride hail allocation manager
// TODO: Build RHM from XML to be able to specify different kinds of TNC/Rideshare types and attributes
// TODO: remove name variable, as not used currently in the code anywhere?

class RideHailingManager(
                          val beamServices: BeamServices,
                          val scheduler: ActorRef,
                          val router: ActorRef,
                          val boundingBox: Envelope,
                          val surgePricingManager: RideHailSurgePricingManager)
  extends VehicleManager with ActorLogging with HasServices {

  implicit val timeout: Timeout = Timeout(50000, TimeUnit.SECONDS)

  override val resources: mutable.Map[Id[BeamVehicle], BeamVehicle] = mutable.Map[Id[BeamVehicle], BeamVehicle]()

  private val vehicleFuelLevel: mutable.Map[Id[Vehicle], Double] = mutable.Map[Id[Vehicle], Double]()

  private val DefaultCostPerMinute = BigDecimal(beamServices.beamConfig.beam.agentsim.agents.rideHail.defaultCostPerMinute)
  private val DefaultCostPerSecond = DefaultCostPerMinute / 60.0d

  private val rideHailAllocationManagerTimeoutInSeconds = {
    beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.timeoutInSeconds
  }

  val allocationManager: String = beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.name


  val tncIterationStats: Option[TNCIterationStats] = {
    val rideHailIterationHistoryActor = context.actorSelection("/user/rideHailIterationHistoryActor")
    val future = rideHailIterationHistoryActor.ask(GetCurrentIterationRideHailStats)
    Await.result(future, timeout.duration).asInstanceOf[Option[TNCIterationStats]]
  }
  tncIterationStats.foreach(_.logMap())


  val rideHailResourceAllocationManager: RideHailResourceAllocationManager = allocationManager match {
    case RideHailResourceAllocationManager.DEFAULT_MANAGER =>
      new DefaultRideHailResourceAllocationManager()
    case RideHailResourceAllocationManager.STANFORD_V1 =>
      new StanfordRideHailAllocationManagerV1(this, rideHailNetworkApi)
    case RideHailResourceAllocationManager.BUFFERED_IMPL_TEMPLATE =>
      new RideHailAllocationManagerBufferedImplTemplate(this)
    case RideHailResourceAllocationManager.REPOSITIONING_LOW_WAITING_TIMES =>
      new RepositioningLowWaitingTimes(this, tncIterationStats)
    case RideHailResourceAllocationManager.RANDOM_REPOSITIONING =>
      new RandomRepositioning(this)
    case _ =>
      throw new IllegalStateException(s"unknonwn RideHailResourceAllocationManager: $allocationManager")
  }


  val modifyPassengerScheduleManager = new RideHailModifyPassengerScheduleManager(log, self, rideHailAllocationManagerTimeoutInSeconds, scheduler, beamServices.beamConfig)

  private var bufferedReserveRideMessages = mutable.Map[String, RideHailingRequest]()

  private val handleRideHailInquirySubmitted = mutable.Set[String]()

  var nextCompleteNoticeRideHailAllocationTimeout: CompletionNotice = _

  beamServices.beamRouter ! GetTravelTime
  beamServices.beamRouter ! GetMatSimNetwork

  var rideHailNetworkApi: RideHailNetworkAPI = new RideHailNetworkAPI()


  //TODO improve search to take into account time when available
  private val availableRideHailingAgentSpatialIndex = {
    new QuadTree[RideHailingAgentLocation](
      boundingBox.getMinX,
      boundingBox.getMinY,
      boundingBox.getMaxX,
      boundingBox.getMaxY)
  }

  private val inServiceRideHailingAgentSpatialIndex = {
    new QuadTree[RideHailingAgentLocation](
      boundingBox.getMinX,
      boundingBox.getMinY,
      boundingBox.getMaxX,
      boundingBox.getMaxY)
  }
  private val availableRideHailVehicles = concurrent.TrieMap[Id[Vehicle], RideHailingAgentLocation]()
  private val inServiceRideHailVehicles = concurrent.TrieMap[Id[Vehicle], RideHailingAgentLocation]()

  /**
    * Customer inquiries awaiting reservation confirmation.
    */
  lazy val travelProposalCache: Cache[String, TravelProposal] = {
    CacheBuilder.newBuilder()
      .maximumSize((10 * beamServices.beamConfig.beam.agentsim.agents.rideHail.numDriversAsFractionOfPopulation * beamServices.beamConfig.beam.agentsim.numAgents).toLong)
      .expireAfterWrite(1, TimeUnit.MINUTES)
      .build()
  }
  private val pendingModifyPassengerScheduleAcks = collection.concurrent.TrieMap[String, RideHailingResponse]()
  private var lockedVehicles = Set[Id[Vehicle]]()

  //context.actorSelection("user/")
  //rideHailIterationHistoryActor send message to ridheailiterationhsitoryactor


  DebugLib.emptyFunctionForSettingBreakPoint()

  override def receive: Receive = {
    case NotifyIterationEnds() =>
      surgePricingManager.incrementIteration()

      sender ! Unit // return empty object to blocking caller

    case RegisterResource(vehId: Id[Vehicle]) =>
      resources.put(agentsim.vehicleId2BeamVehicleId(vehId), beamServices.vehicles(vehId))

    case NotifyResourceIdle(vehicleId: Id[Vehicle], whenWhere, passengerSchedule) =>
      updateLocationOfAgent(vehicleId, whenWhere, isAvailable = isAvailable(vehicleId))

      //updateLocationOfAgent(vehicleId, whenWhere, isAvailable = true)
      resources(agentsim.vehicleId2BeamVehicleId(vehicleId)).driver.foreach(driver => {
        val rideHailingAgentLocation = RideHailingAgentLocation(driver, vehicleId, whenWhere)
        if (modifyPassengerScheduleManager.noPendingReservations(vehicleId) || modifyPassengerScheduleManager.isPendingReservationEnding(vehicleId, passengerSchedule)) {
          log.debug("Making available: {}", vehicleId)
          // we still might have some ongoing resrvation in going on
          makeAvailable(rideHailingAgentLocation)
        }
        modifyPassengerScheduleManager.checkInResource(vehicleId, Some(whenWhere), Some(passengerSchedule))
        driver ! GetBeamVehicleFuelLevel
      })


    case NotifyResourceInUse(vehId: Id[Vehicle], whenWhere) =>
      updateLocationOfAgent(vehId, whenWhere, isAvailable = false)


    case BeamVehicleFuelLevelUpdate(id, fuelLevel) =>
      vehicleFuelLevel.put(id, fuelLevel)

    case Finish =>
      log.info("received Finish message")

    case MATSimNetwork(network) =>
      rideHailNetworkApi.setMATSimNetwork(network)

    case CheckInResource(vehicleId: Id[Vehicle], whenWhere) =>
      updateLocationOfAgent(vehicleId, whenWhere.get, isAvailable = true)

      if (whenWhere.get.time == 0) {
        resources(agentsim.vehicleId2BeamVehicleId(vehicleId)).driver.foreach(driver => {
          val rideHailingAgentLocation = RideHailingAgentLocation(driver, vehicleId, whenWhere.get)
          if (modifyPassengerScheduleManager.noPendingReservations(vehicleId)) {
            // we still might have some ongoing resrvation in going on
            log.debug("Checking in: {}", vehicleId)
            makeAvailable(rideHailingAgentLocation)
          }
          sender ! CheckInSuccess
          log.debug("checking in resource: vehicleId({});availableIn.time({})", vehicleId, whenWhere.get.time)
          modifyPassengerScheduleManager.checkInResource(vehicleId, whenWhere, None)
          driver ! GetBeamVehicleFuelLevel
        })
      }

    case CheckOutResource(_) =>
      // Because the RideHail Manager is in charge of deciding which specific vehicles to assign to customers, this should never be used
      throw new RuntimeException("Illegal use of CheckOutResource, RideHailingManager is responsible for checking out vehicles in fleet.")

    case inquiry@RideHailingRequest(RideHailingInquiry, _, _, _, _) =>
      if (!findDriverAndSendRoutingRequests(inquiry)) {
        inquiry.customer.personRef.get ! RideHailingResponse(inquiry, None, error = Some(CouldNotFindRouteToCustomer))
      }

    case R5Network(network) =>
      rideHailNetworkApi.setR5Network(network)

    case RoutingResponses(request, rideHailingLocation, rideHailingAgent2CustomerResponse, rideHailing2DestinationResponse) =>
      val itins2Cust = rideHailingAgent2CustomerResponse.itineraries.filter(x => x.tripClassifier.equals(RIDE_HAIL))
      val itins2Dest = rideHailing2DestinationResponse.itineraries.filter(x => x.tripClassifier.equals(RIDE_HAIL))

      val rideHailingFarePerSecond = DefaultCostPerSecond * surgePricingManager.getSurgeLevel(request.pickUpLocation,
        request.departAt.atTime.toDouble)
      val customerPlans2Costs: Map[RoutingModel.EmbodiedBeamTrip, BigDecimal] = itins2Dest.map(t => (t, rideHailingFarePerSecond * t.totalTravelTimeInSecs)).toMap

      if (itins2Cust.nonEmpty && itins2Dest.nonEmpty) {
        val (customerTripPlan, cost) = customerPlans2Costs.minBy(_._2)
        val tripDriver2Cust = RoutingResponse(Vector(itins2Cust.head.copy(legs = itins2Cust.head.legs.map(l => l.copy(asDriver = true)))))
        val timeToCustomer = tripDriver2Cust.itineraries.head.totalTravelTimeInSecs

        val tripCust2Dest = RoutingResponse(Vector(customerTripPlan.copy(legs = customerTripPlan.legs.zipWithIndex.map(legWithInd =>
          legWithInd._1.copy(asDriver = legWithInd._1.beamLeg.mode == WALK,
            unbecomeDriverOnCompletion = legWithInd._2 == 2,
            beamLeg = legWithInd._1.beamLeg.copy(startTime = legWithInd._1.beamLeg.startTime + timeToCustomer),
            cost = if (legWithInd._1.beamLeg == customerTripPlan.legs(1).beamLeg) {
              cost
            } else {
              0.0
            }
          )))))

        val travelProposal = TravelProposal(rideHailingLocation, timeToCustomer, cost, Some(FiniteDuration
        (customerTripPlan.totalTravelTimeInSecs, TimeUnit.SECONDS)), tripDriver2Cust, tripCust2Dest)

        travelProposalCache.put(request.requestId.toString, travelProposal)

        //        log.debug(s"Found ridehail ${rideHailingLocation.vehicleId} for person=${request.customer.personId} and ${request.requestType} " +
        //          s"requestId=${request.requestId}, timeToCustomer=$timeToCustomer seconds and cost=$$$cost")

        request.requestType match {
          case RideHailingInquiry =>
            request.customer.personRef.get ! RideHailingResponse(request, Some(travelProposal))
          case ReserveRide =>
            self ! request
        }
        //        customerAgent match {
        //          case Some(customerActor) =>
        //          case None =>
        //            bufferedReserveRideMessages.get(requestId) match {
        //              case Some(ReserveRide(localInquiryId, vehiclePersonId, localCustomerPickUp, localDepartAt, destination)) =>
        //                handleReservationRequest(localInquiryId, vehiclePersonId, localCustomerPickUp, localDepartAt, destination)
        //                bufferedReserveRideMessages.remove(localInquiryId)
        //                handleRideHailInquirySubmitted.remove(localInquiryId)
        //
        //                // TODO if there is any issue related to bufferedReserveRideMessages, look at this closer (e.g. make two data structures, one for those
        //                // before timout and one after
        //                if (handleRideHailInquirySubmitted.size == 0) {
        //                  modifyPassengerScheduleManager.sendoutAckMessageToSchedulerForRideHailAllocationmanagerTimeout()
        //                }
        //            }
        // call was made by ride hail agent itself
        //        }
      } else {
        log.debug("Router could not find route to customer person={} for requestId={}", request.customer.personId, request.requestId)
        request.customer.personRef.get ! RideHailingResponse(request, None, error = Some(CouldNotFindRouteToCustomer))
      }

    case reserveRide@RideHailingRequest(ReserveRide, _, _, _, _) =>
      if (rideHailResourceAllocationManager.isBufferedRideHailAllocationMode) {
        val requestId = reserveRide.requestId
        bufferedReserveRideMessages += (requestId.toString -> reserveRide)
        //System.out.println("")
      } else {
        handleReservationRequest(reserveRide)
      }


    case modifyPassengerScheduleAck@ModifyPassengerScheduleAck(requestIdOpt, triggersToSchedule, _) =>
      log.debug("modifyPassengerScheduleAck received: " + modifyPassengerScheduleAck)
      requestIdOpt match {
        case None =>
          modifyPassengerScheduleManager.modifyPassengerScheduleAckReceivedForRepositioning(triggersToSchedule)
        // val newTriggers = triggersToSchedule ++ nextCompleteNoticeRideHailAllocationTimeout.newTriggers
        // scheduler ! CompletionNotice(nextCompleteNoticeRideHailAllocationTimeout.id, newTriggers)
        case Some(requestId) =>
          completeReservation(requestId, triggersToSchedule)
      }

    case UpdateTravelTime(travelTime) =>
      rideHailNetworkApi.setTravelTime(travelTime)

    case DebugRideHailManagerDuringExecution =>
      modifyPassengerScheduleManager.printState()

    case TriggerWithId(RideHailAllocationManagerTimeout(tick), triggerId) =>

      val produceDebugImages = false
      if (produceDebugImages) {
        if (tick > 0 && tick.toInt % 3600 == 0 && tick < 24 * 3600) {
          val spatialPlot = new SpatialPlot(1100, 1100,50)

          for (veh <- resources.values) {
            spatialPlot.addPoint(PointToPlot(getRideHailAgentLocation(veh.id).currentLocation.loc, Color.BLACK, 5))
          }


          tncIterationStats.foreach(tncIterationStats => {

            val tazEntries = tncIterationStats getCoordinatesWithRideHailStatsEntry(tick, tick + 3600)

            for (tazEntry <- tazEntries.filter(x => x._2.sumOfRequestedRides > 0)) {
              spatialPlot.addPoint(PointToPlot(tazEntry._1, Color.RED, 10 + Math.log(tazEntry._2.sumOfRequestedRides).toInt))
            }
          })

          spatialPlot.writeImage(beamServices.matsimServices.getControlerIO.getIterationFilename(beamServices.iterationNumber, tick.toInt / 3600 + "locationOfAgentsInitally.png"))
        }
      }

      modifyPassengerScheduleManager.startWaiveOfRepositioningRequests(tick, triggerId)

      log.debug("getIdleVehicles().size:{}", getIdleVehicles.size)
      getIdleVehicles.foreach(x => log.debug("getIdleVehicles(): {}",  x._1.toString))

      val repositionVehicles: Vector[(Id[Vehicle], Location)] = rideHailResourceAllocationManager.repositionVehicles(tick)

      if (repositionVehicles.isEmpty) {
        modifyPassengerScheduleManager.sendoutAckMessageToSchedulerForRideHailAllocationmanagerTimeout()
      } else {
        modifyPassengerScheduleManager.setNumberOfRepositioningsToProcess(repositionVehicles.size)


        //   printRepositionDistanceSum(repositionVehicles)
      }

      for (repositionVehicle <- repositionVehicles) {

        val (vehicleId, destinationLocation) = repositionVehicle

        if (getIdleVehicles.contains(vehicleId)) {
          val rideHailAgentLocation = getIdleVehicles(vehicleId)

          val rideHailAgent = rideHailAgentLocation.agentRef

          val rideHailingVehicleAtOrigin = StreetVehicle(rideHailAgentLocation.vehicleId, SpaceTime(
            (rideHailAgentLocation.currentLocation.loc, tick.toLong)), CAR, asDriver = false)

          val departAt = DiscreteTime(tick.toInt)

          val routingRequest = RoutingRequest(
            origin = rideHailAgentLocation.currentLocation.loc,
            destination = destinationLocation,
            departureTime = departAt,
            transitModes = Vector(),
            streetVehicles = Vector(rideHailingVehicleAtOrigin)
          )
          val futureRideHailingAgent2CustomerResponse = router ? routingRequest

          for {
            rideHailingAgent2CustomerResponse <- futureRideHailingAgent2CustomerResponse.mapTo[RoutingResponse]
          } {
            val itins2Cust = rideHailingAgent2CustomerResponse.itineraries.filter(x => x.tripClassifier.equals(RIDE_HAIL))

            if (itins2Cust.nonEmpty) {
              val modRHA2Cust: Vector[RoutingModel.EmbodiedBeamTrip] = itins2Cust.map(l => l.copy(legs = l.legs.map(c => c.copy(asDriver = true))))
              val rideHailingAgent2CustomerResponseMod = RoutingResponse(modRHA2Cust)

              // TODO: extract creation of route to separate method?
              val passengerSchedule = PassengerSchedule().addLegs(rideHailingAgent2CustomerResponseMod.itineraries.head.toBeamTrip().legs)

              self ! RepositionVehicleRequest(passengerSchedule, tick, vehicleId, rideHailAgent)

            } else {
              self ! ReduceAwaitingRepositioningAckMessagesByOne
            }
          }

        } else {
          modifyPassengerScheduleManager.modifyPassengerScheduleAckReceivedForRepositioning(Vector())
        }
      }

    case ReduceAwaitingRepositioningAckMessagesByOne =>
      modifyPassengerScheduleManager.modifyPassengerScheduleAckReceivedForRepositioning(Vector())

    case RepositionVehicleRequest(passengerSchedule, tick, vehicleId, rideHailAgent) =>

      // TODO: send following to a new case, which handles it
      // -> code for sending message could be prepared in modifyPassengerScheduleManager
      // e.g. create case class
      log.debug("RideHailAllocationManagerTimeout: requesting to send interrupt message to vehicle for repositioning: " + vehicleId)
      modifyPassengerScheduleManager.repositionVehicle(passengerSchedule, tick, vehicleId, rideHailAgent)
    //repositioningPassengerSchedule.put(vehicleId,(rideHailAgentInterruptId, Some(passengerSchedule)))

    case InterruptedWhileIdle(interruptId, vehicleId, tick) =>
      modifyPassengerScheduleManager.handleInterrupt(InterruptedWhileIdle.getClass, interruptId, None, vehicleId, tick)

    case InterruptedAt(interruptId, interruptedPassengerSchedule, _, vehicleId, tick) =>
      modifyPassengerScheduleManager.handleInterrupt(InterruptedAt.getClass, interruptId, Some(interruptedPassengerSchedule), vehicleId, tick)

    case Finish =>
      log.info("finish message received from BeamAgentScheduler")

    case msg =>
      log.warning(s"unknown message received by RideHailingManager $msg")

  }

  private def printRepositionDistanceSum(repositionVehicles: Vector[(Id[Vehicle], Location)]) = {
    var sumOfDistances: Double = 0
    var numberOfTrips = 0;
    for (repositionVehicle <- repositionVehicles) {
      val (vehicleId, destinationLocation) = repositionVehicle
      val rideHailAgentLocation = getIdleVehicles(vehicleId)

      sumOfDistances += beamServices.geo.distInMeters(rideHailAgentLocation.currentLocation.loc, destinationLocation)
      numberOfTrips += 1
    }

    //println(s"sumOfDistances: $sumOfDistances - numberOfTrips: $numberOfTrips")

    DebugLib.emptyFunctionForSettingBreakPoint()
  }

  // Returns Boolean indicating success/failure
  def findDriverAndSendRoutingRequests(request: RideHailingRequest): Boolean = {

    val vehicleAllocationRequest = VehicleAllocationRequest(request.pickUpLocation, request.departAt, request.destination, isInquiry = true)

    val rideHailLocationOpt = rideHailResourceAllocationManager.proposeVehicleAllocation(vehicleAllocationRequest) match {
      case Some(allocation) =>
        // TODO (RW): Test following code with stanford class
        val rideHailAgent = resources.get(agentsim.vehicleId2BeamVehicleId(allocation.vehicleId)).orElse(beamServices.vehicles.get(allocation.vehicleId)).get.driver.head
        Some(RideHailingAgentLocation(rideHailAgent, allocation.vehicleId, allocation.availableAt))
      case None =>
        // use default allocation manager
        getClosestIdleRideHailingAgent(request.pickUpLocation, radiusInMeters)
    }
    rideHailLocationOpt match {
      case Some(rhLocation) =>
        requestRoutesToCustomerAndDestination(request, rhLocation)
        true
      case None =>
        false
    }
  }


  private def getRideHailAgent(vehicleId: Id[Vehicle]): ActorRef = {
    getRideHailAgentLocation(vehicleId).agentRef
  }

  def getRideHailAgentLocation(vehicleId: Id[Vehicle]): RideHailingAgentLocation = {
    getIdleVehicles.getOrElse(vehicleId, inServiceRideHailVehicles(vehicleId))
  }

  /*
  //TODO requires proposal in cache
  private def findClosestRideHailingAgents(requestId: Int, customerPickUp: Location) = {
    val travelPlanOpt = Option(travelProposalCache.asMap.remove(requestId))
    /**
      * 1. customerAgent ! ReserveRideConfirmation(availableRideHailingAgentSpatialIndex, customerId, travelProposal)
      * 2. availableRideHailingAgentSpatialIndex ! PickupCustomer
      */
    val nearbyRideHailingAgents = availableRideHailingAgentSpatialIndex.getDisk(customerPickUp.getX, customerPickUp.getY,
      radiusInMeters).asScala.toVector
    val closestRHA: Option[RideHailingAgentLocation] = nearbyRideHailingAgents.filter(x =>
      lockedVehicles(x.vehicleId)).find(_.vehicleId.equals(travelPlanOpt.get.responseRideHailing2Pickup
      .itineraries.head.vehiclesInTrip.head))
    (travelPlanOpt, closestRHA)
  }
  */

  private def sendRoutingRequests(personId: Id[PersonAgent], customerPickUp: Location, departAt: BeamTime,
                                  destination: Location, rideHailingLocation: RideHailingAgentLocation): (Future[Any], Future[Any]) = {
    val customerAgentBody = StreetVehicle(Id.createVehicleId(s"body-$personId"), SpaceTime((customerPickUp,
      departAt.atTime)), WALK, asDriver = true)
    val rideHailingVehicleAtOrigin = StreetVehicle(rideHailingLocation.vehicleId, SpaceTime(
      (rideHailingLocation.currentLocation.loc, departAt.atTime)), CAR, asDriver = false)
    val rideHailingVehicleAtPickup = StreetVehicle(rideHailingLocation.vehicleId, SpaceTime((customerPickUp,
      departAt.atTime)), CAR, asDriver = false)

    //TODO: Error handling. In the (unlikely) event of a timeout, this RideHailingManager will silently be
    //TODO: restarted, and probably someone will wait forever for its reply.

    // get route from ride hailing vehicle to customer
    val futureRideHailingAgent2CustomerResponse = router ? RoutingRequest(rideHailingLocation
      .currentLocation.loc, customerPickUp, departAt, Vector(), Vector(rideHailingVehicleAtOrigin))
    //XXXX: customer trip request might be redundant... possibly pass in info

    // get route from customer to destination
    val futureRideHailing2DestinationResponse = router ? RoutingRequest(customerPickUp, destination, departAt, Vector(), Vector(customerAgentBody, rideHailingVehicleAtPickup))
    (futureRideHailingAgent2CustomerResponse, futureRideHailing2DestinationResponse)
  }

  private def isAvailable(vehicleId: Id[Vehicle]): Boolean = {
    availableRideHailVehicles.contains(vehicleId)
  }


  private def updateLocationOfAgent(vehicleId: Id[Vehicle], whenWhere: SpaceTime, isAvailable: Boolean) = {
    if (isAvailable) {
      availableRideHailVehicles.get(vehicleId) match {
        case Some(prevLocation) =>
          val newLocation = prevLocation.copy(currentLocation = whenWhere)
          availableRideHailingAgentSpatialIndex.remove(prevLocation.currentLocation.loc.getX, prevLocation.currentLocation.loc.getY, prevLocation)
          availableRideHailingAgentSpatialIndex.put(newLocation.currentLocation.loc.getX, newLocation.currentLocation.loc.getY, newLocation)
          availableRideHailVehicles.put(newLocation.vehicleId, newLocation)
        case None =>
      }
    } else {
      inServiceRideHailVehicles.get(vehicleId) match {
        case Some(prevLocation) =>
          val newLocation = prevLocation.copy(currentLocation = whenWhere)
          inServiceRideHailingAgentSpatialIndex.remove(prevLocation.currentLocation.loc.getX, prevLocation.currentLocation.loc.getY, prevLocation)
          inServiceRideHailingAgentSpatialIndex.put(newLocation.currentLocation.loc.getX, newLocation.currentLocation.loc.getY, newLocation)
          inServiceRideHailVehicles.put(newLocation.vehicleId, newLocation)
        case None =>
      }
    }
  }

  private def makeAvailable(agentLocation: RideHailingAgentLocation) = {
    availableRideHailVehicles.put(agentLocation.vehicleId, agentLocation)
    availableRideHailingAgentSpatialIndex.put(agentLocation.currentLocation.loc.getX,
      agentLocation.currentLocation.loc.getY, agentLocation)
    inServiceRideHailVehicles.remove(agentLocation.vehicleId)
    inServiceRideHailingAgentSpatialIndex.remove(agentLocation.currentLocation.loc.getX,
      agentLocation.currentLocation.loc.getY, agentLocation)
  }

  private def putIntoService(agentLocation: RideHailingAgentLocation) = {
    availableRideHailVehicles.remove(agentLocation.vehicleId)
    availableRideHailingAgentSpatialIndex.remove(agentLocation.currentLocation.loc.getX,
      agentLocation.currentLocation.loc.getY, agentLocation)
    inServiceRideHailVehicles.put(agentLocation.vehicleId, agentLocation)
    inServiceRideHailingAgentSpatialIndex.put(agentLocation.currentLocation.loc.getX,
      agentLocation.currentLocation.loc.getY, agentLocation)
  }

  private def handleReservation(request: RideHailingRequest, travelProposal: TravelProposal): Unit = {

    surgePricingManager.addRideCost(request.departAt.atTime, travelProposal.estimatedPrice.doubleValue(), request.pickUpLocation)

    // Modify RH agent passenger schedule and create BeamAgentScheduler message that will dispatch RH agent to do the
    // pickup
    val passengerSchedule = PassengerSchedule()
      .addLegs(travelProposal.responseRideHailing2Pickup.itineraries.head.toBeamTrip().legs) // Adds empty trip to customer
      .addPassenger(request.customer, travelProposal.responseRideHailing2Dest.itineraries.head.legs.filter(_.isRideHail).map(_.beamLeg)) // Adds customer's actual trip to destination
    putIntoService(travelProposal.rideHailingAgentLocation)
    lockVehicle(travelProposal.rideHailingAgentLocation.vehicleId)

    // Create confirmation info but stash until we receive ModifyPassengerScheduleAck
    val tripLegs = travelProposal.responseRideHailing2Dest.itineraries.head.legs.map(_.beamLeg)
    pendingModifyPassengerScheduleAcks.put(request.requestId.toString, RideHailingResponse(request, Some(travelProposal)))

    log.debug("Reserving vehicle: {} customer: {} request: {}",
      travelProposal.rideHailingAgentLocation.vehicleId, request.customer.personId, request.requestId)

    modifyPassengerScheduleManager.reserveVehicle(passengerSchedule, passengerSchedule.schedule.head._1.startTime,
      travelProposal.rideHailingAgentLocation, Some(request.requestId))
  }

  private def completeReservation(requestId: Int, triggersToSchedule: Seq[ScheduleTrigger]): Unit = {
    pendingModifyPassengerScheduleAcks.remove(requestId.toString) match {
      case Some(response) =>
        log.debug("Completing reservation for {}", requestId)
        unlockVehicle(response.travelProposal.get.rideHailingAgentLocation.vehicleId)
        response.request.customer.personRef.get ! response.copy(triggersToSchedule = triggersToSchedule.toVector)
      case None =>
        log.error(s"Vehicle was reserved by another agent for inquiry id $requestId")
        sender() ! RideHailingResponse.dummyWithError(RideHailVehicleTakenError)
    }
  }


  def getClosestIdleVehiclesWithinRadius(pickupLocation: Coord, radius: Double): Vector[RideHailingAgentLocation] = {
    val nearbyRideHailingAgents = availableRideHailingAgentSpatialIndex.getDisk(pickupLocation.getX, pickupLocation.getY,
      radius).asScala.toVector
    val distances2RideHailingAgents = nearbyRideHailingAgents.map(rideHailingAgentLocation => {
      val distance = CoordUtils.calcProjectedEuclideanDistance(pickupLocation, rideHailingAgentLocation
        .currentLocation.loc)
      (rideHailingAgentLocation, distance)
    })
    //TODO: Possibly get multiple taxis in this block
    val result = distances2RideHailingAgents.filter(x => availableRideHailVehicles.contains(x._1.vehicleId)).sortBy(_._2).map(_._1)
    if (result.isEmpty) {
      val i = 0
    }
    result
  }

  def getClosestIdleRideHailingAgent(pickupLocation: Coord,
                                     radius: Double): Option[RideHailingAgentLocation] = {
    getClosestIdleVehiclesWithinRadius(pickupLocation, radius).headOption
  }


  private def handleReservationRequest(request: RideHailingRequest): Unit = {
    //    log.debug(s"handleReservationRequest: $request")
    Option(travelProposalCache.getIfPresent(request.requestId.toString)) match {
      case Some(travelProposal) =>
        if (inServiceRideHailVehicles.contains(travelProposal.rideHailingAgentLocation.vehicleId) ||
          lockedVehicles.contains(travelProposal.rideHailingAgentLocation.vehicleId)) {
          if (!findDriverAndSendRoutingRequests(request)) {
            request.customer.personRef.get ! RideHailingResponse(request, None, error = Some(CouldNotFindRouteToCustomer))
          }
        } else {
          handleReservation(request, travelProposal)
        }
      case None =>
        if (!findDriverAndSendRoutingRequests(request)) {
          request.customer.personRef.get ! RideHailingResponse(request, None, error = Some(CouldNotFindRouteToCustomer))
        }
    }
  }

  def unlockVehicle(vehicleId: Id[Vehicle]): Unit = {
    lockedVehicles -= vehicleId
  }

  def lockVehicle(vehicleId: Id[Vehicle]): Unit = {
    lockedVehicles += vehicleId
  }

  def getVehicleFuelLevel(vehicleId: Id[Vehicle]): Double = vehicleFuelLevel(vehicleId)

  def getIdleVehicles: collection.concurrent.TrieMap[Id[Vehicle], RideHailingAgentLocation] = {
    availableRideHailVehicles
  }

  private def requestRoutesToCustomerAndDestination(request: RideHailingRequest,
                                                    rideHailLocation: RideHailingAgentLocation): Unit = {

    val pickupSpaceTime = SpaceTime((request.pickUpLocation, request.departAt.atTime))
    val customerAgentBody = StreetVehicle(request.customer.vehicleId, pickupSpaceTime, WALK, asDriver = true)
    val rideHailingVehicleAtOrigin = StreetVehicle(rideHailLocation.vehicleId,
      SpaceTime((rideHailLocation.currentLocation.loc, request.departAt.atTime)), CAR, asDriver = false)
    val rideHailingVehicleAtPickup = StreetVehicle(rideHailLocation.vehicleId, pickupSpaceTime, CAR, asDriver = false)

    // get route from ride hailing vehicle to customer
    val futureRideHailingAgent2CustomerResponse = router ? RoutingRequest(rideHailLocation.currentLocation.loc,
      request.pickUpLocation, request.departAt, Vector(), Vector(rideHailingVehicleAtOrigin))
    // get route from customer to destination
    val futureRideHailing2DestinationResponse = router ? RoutingRequest(request.pickUpLocation, request.destination,
      request.departAt, Vector(), Vector(customerAgentBody, rideHailingVehicleAtPickup))

    for {
      rideHailingAgent2CustomerResponse <- futureRideHailingAgent2CustomerResponse.mapTo[RoutingResponse]
      rideHailing2DestinationResponse <- futureRideHailing2DestinationResponse.mapTo[RoutingResponse]
    } {
      // TODO: could we just call the code, instead of sending the message here?
      self ! RoutingResponses(request, rideHailLocation, rideHailingAgent2CustomerResponse, rideHailing2DestinationResponse)
    }
  }

}


object RideHailingManager {
  val dummyID: Id[RideHailingRequest] = Id.create("dummyInquiryId", classOf[RideHailingRequest])
  val radiusInMeters: Double = 5000d

  val INITIAL_RIDEHAIL_LOCATION_HOME = "HOME"
  val INITIAL_RIDEHAIL_LOCATION_UNIFORM_RANDOM = "UNIFORM_RANDOM"
  val INITIAL_RIDEHAIL_LOCATION_ALL_AT_CENTER = "ALL_AT_CENTER"

  def nextRideHailingInquiryId: Id[RideHailingRequest] = {
    Id.create(UUIDGen.createTime(UUIDGen.newTime()).toString, classOf[RideHailingRequest])
  }


  case class NotifyIterationEnds()

  sealed trait RideHailingRequestType

  case object RideHailingInquiry extends RideHailingRequestType

  case object ReserveRide extends RideHailingRequestType

  case class RideHailingRequest(requestType: RideHailingRequestType,
                                customer: VehiclePersonId,
                                pickUpLocation: Location,
                                departAt: BeamTime,
                                destination: Location) {
    // We make requestId be independent of request type, all that matters is details of the customer
    lazy val requestId: Int = this.copy(requestType = RideHailingInquiry).hashCode()
  }

  object RideHailingRequest {
    val dummy = RideHailingRequest(RideHailingInquiry,
      VehiclePersonId(Id.create("dummy", classOf[Vehicle]), Id.create("dummy", classOf[Person])),
      new Coord(Double.NaN, Double.NaN),
      DiscreteTime(Int.MaxValue),
      new Coord(Double.NaN, Double.NaN)
    )
  }

  case class TravelProposal(rideHailingAgentLocation: RideHailingAgentLocation,
                            timeToCustomer: Long,
                            estimatedPrice: BigDecimal,
                            estimatedTravelTime: Option[Duration],
                            responseRideHailing2Pickup: RoutingResponse,
                            responseRideHailing2Dest: RoutingResponse)

  case class RideHailingResponse(request: RideHailingRequest,
                                 travelProposal: Option[TravelProposal],
                                 error: Option[ReservationError] = None,
                                 triggersToSchedule: Vector[ScheduleTrigger] = Vector())

  object RideHailingResponse {
    val dummy = RideHailingResponse(RideHailingRequest.dummy, None, None)

    def dummyWithError(error: ReservationError) = RideHailingResponse(RideHailingRequest.dummy, None, Some(error))
  }

  private case class RoutingResponses(request: RideHailingRequest,
                                      rideHailingLocation: RideHailingAgentLocation,
                                      rideHailingAgent2CustomerResponse: RoutingResponse,
                                      rideHailing2DestinationResponse: RoutingResponse)

  case class RegisterRideAvailable(rideHailingAgent: ActorRef, vehicleId: Id[Vehicle], availableSince: SpaceTime)

  case class RegisterRideUnavailable(ref: ActorRef, location: Coord)

  case class RideHailingAgentLocation(agentRef: ActorRef, vehicleId: Id[Vehicle], currentLocation: SpaceTime)

  case object RideUnavailableAck

  case object RideAvailableAck

  case object DebugRideHailManagerDuringExecution

  case class RepositionResponse(rnd1: RideHailingAgentLocation,
                                rnd2: RideHailingManager.RideHailingAgentLocation,
                                rnd1Response: RoutingResponse,
                                rnd2Response: RoutingResponse)

  case class RideHailAllocationManagerTimeout(tick: Double) extends Trigger


  def props(services: BeamServices,
            scheduler: ActorRef,
            router: ActorRef,
            boundingBox: Envelope,
            surgePricingManager:
            RideHailSurgePricingManager): Props = {
    Props(new RideHailingManager(services, scheduler, router, boundingBox, surgePricingManager))
  }
}

