package beam.agentsim.agents.rideHail

import java.time.temporal.ChronoUnit
import java.time.{ZoneOffset, ZonedDateTime}
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import java.util.concurrent.{Executors, TimeUnit}

import beam.agentsim.agents.BeamAgent.Finish
import akka.actor.{ActorLogging, ActorRef, Props}
import akka.pattern._
import akka.util.Timeout
import beam.agentsim
import beam.agentsim.Resource._
import beam.agentsim.ResourceManager.VehicleManager
import beam.agentsim.agents.PersonAgent
import beam.agentsim.agents.household.HouseholdActor.ReleaseVehicleReservation
import beam.agentsim.agents.rideHail.RideHailingAgent.{ModifyPassengerSchedule, ModifyPassengerScheduleAck}
import beam.agentsim.agents.rideHail.RideHailingManager._
import beam.agentsim.agents.vehicles.AccessErrorCodes.{CouldNotFindRouteToCustomer, RideHailVehicleTakenError, UnknownInquiryIdError, UnknownRideHailReservationError}
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.agents.vehicles._
import beam.agentsim.events.SpaceTime
import beam.agentsim.events.resources.ReservationError
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.{Trigger, TriggerWithId}
import beam.analysis.plots.GraphRideHailingRevenue
import beam.router.BeamRouter.{Location, RoutingRequest, RoutingResponse}
import beam.router.Modes.BeamMode._
import beam.router.RoutingModel
import beam.router.RoutingModel.{BeamTime, BeamTrip, DiscreteTime}
import beam.router.r5.Statistics
import beam.sim.{BeamServices, HasServices}
import com.eaio.uuid.UUIDGen
import com.google.common.cache.{Cache, CacheBuilder}
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.collections.QuadTree
import org.matsim.core.utils.geometry.CoordUtils
import org.matsim.vehicles.Vehicle

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.Random
import scala.concurrent.duration._


object RoutingRequestResponseStats {
  private val fullTime: mutable.ArrayBuffer[Double] = mutable.ArrayBuffer.empty[Double]
  private val requestTravelTime: mutable.ArrayBuffer[Double] = mutable.ArrayBuffer.empty[Double]
  private val responseTravelTime: mutable.ArrayBuffer[Double] = mutable.ArrayBuffer.empty[Double]

  def add(resp: RoutingResponse): Unit = {
    fullTime.synchronized {
      fullTime += ChronoUnit.MILLIS.between(resp.requestCreatedAt, resp.receivedAt.get)
    }
    requestTravelTime.synchronized {
      requestTravelTime += ChronoUnit.MILLIS.between(resp.requestCreatedAt, resp.requestReceivedAt)
    }

    responseTravelTime.synchronized {
      responseTravelTime += ChronoUnit.MILLIS.between(resp.createdAt, resp.receivedAt.get)
    }
  }

  def fullTimeStat: Statistics = {
    fullTime.synchronized {
      Statistics(fullTime)
    }
  }

  def requestTravelTimeStat: Statistics = {
    requestTravelTime.synchronized {
      Statistics(requestTravelTime)
    }
  }

  def responseTravelTimeStat: Statistics = {
    responseTravelTime.synchronized {
      Statistics(responseTravelTime)
    }
  }
}

object RoutingRequestSenderCounter {
  private val n: AtomicLong = new AtomicLong(0L)
  private var startTime: Option[ZonedDateTime] = None
  private val obj: Object = new Object

  def sent(): Unit = {
    obj.synchronized {
      if (startTime.isEmpty)
        startTime = Some(ZonedDateTime.now(ZoneOffset.UTC))
    }
    n.incrementAndGet()
  }
  def rate: Double = {
    val now = ZonedDateTime.now(ZoneOffset.UTC)
    val seconds = ChronoUnit.SECONDS.between(startTime.getOrElse(now), now)
    if (seconds > 0) {
      n.get().toDouble / seconds
    }else {
      0.0
    }
  }
}

class RideHailingManager(val  beamServices: BeamServices, val scheduler: ActorRef,val router: ActorRef, val boundingBox: Envelope, val surgePricingManager: RideHailSurgePricingManager) extends VehicleManager with ActorLogging with HasServices {

  import scala.collection.JavaConverters._

  override val resources: collection.mutable.Map[Id[BeamVehicle], BeamVehicle] = collection.mutable.Map[Id[BeamVehicle], BeamVehicle]()

  // TODO: currently 'DefaultCostPerMile' is not used anywhere in the code, therefore commented it out -> needs to be used!
  // val DefaultCostPerMile = BigDecimal(beamServices.beamConfig.beam.agentsim.agents.rideHailing.defaultCostPerMile)
  val DefaultCostPerMinute = BigDecimal(beamServices.beamConfig.beam.agentsim.agents.rideHailing.defaultCostPerMinute)
  val radius: Double = 5000
  val selfTimerTimoutDuration=10*60 // TODO: set from config

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
  private val availableRideHailVehicles = collection.concurrent.TrieMap[Id[Vehicle], RideHailingAgentLocation]()
  private val inServiceRideHailVehicles = collection.concurrent.TrieMap[Id[Vehicle], RideHailingAgentLocation]()

  /**
    * Customer inquiries awaiting reservation confirmation.
    */
  lazy val pendingInquiries: Cache[Id[RideHailingInquiry], (TravelProposal, BeamTrip)] = CacheBuilder.newBuilder().expireAfterWrite(10, TimeUnit.SECONDS).build()

  private val pendingModifyPassengerScheduleAcks = collection.concurrent.TrieMap[Id[RideHailingInquiry],
    ReservationResponse]()
  private var lockedVehicles = Set[Id[Vehicle]]()

  val tickTask = context.system.scheduler.schedule(2.seconds, 2.seconds, self, "tick")(context.dispatcher)

  val numOfThreads = if (Runtime.getRuntime().availableProcessors() <= 2)  {
    1
  }
  else {
    Runtime.getRuntime().availableProcessors() - 2
  }

  val executionContext: ExecutionContext = ExecutionContext.fromExecutorService(
    Executors.newFixedThreadPool(numOfThreads))

  override def receive: Receive = {
    case "tick" =>
      log.info("rideHailingManager Full time (ms): {}",
          RoutingRequestResponseStats.fullTimeStat)
      log.info("rideHailingManager RoutingRequest travel time (ms): {}",
          RoutingRequestResponseStats.requestTravelTimeStat)
      log.info("rideHailingManager RoutingResponse travel time (ms): {}",
          RoutingRequestResponseStats.responseTravelTimeStat)

      log.info(s"Sending ${RoutingRequestSenderCounter.rate} per seconds of RoutingRequest")

    case NotifyIterationEnds() =>

      surgePricingManager.updateRevenueStats()
      surgePricingManager.updateSurgePriceLevels()
      surgePricingManager.incrementIteration()

      sender ! Unit  // return empty object to blocking caller

    case RegisterResource(vehId: Id[Vehicle]) =>
      resources.put(agentsim.vehicleId2BeamVehicleId(vehId), beamServices.vehicles(vehId))

    case NotifyResourceIdle(vehId: Id[Vehicle], whenWhere) =>
      updateLocationOfAgent(vehId, whenWhere, false)

    case NotifyResourceInUse(vehId: Id[Vehicle], whenWhere) =>
      updateLocationOfAgent(vehId, whenWhere, false)

    case CheckInResource(vehicleId: Id[Vehicle], availableIn: Option[SpaceTime]) =>
      resources.get(agentsim.vehicleId2BeamVehicleId(vehicleId)).orElse(beamServices.vehicles.get(vehicleId)).get.driver.foreach(driver => {
        val rideHailingAgentLocation = RideHailingAgentLocation(driver, vehicleId, availableIn.get)
        makeAvailable(rideHailingAgentLocation)
        sender ! CheckInSuccess
      }     )

    case RepositionResponse(rnd1, rnd2, r1, r2) =>
      RoutingRequestResponseStats.add(r1)
      RoutingRequestResponseStats.add(r2)
      updateLocationOfAgent(rnd1.vehicleId, rnd2.currentLocation, true)
      updateLocationOfAgent(rnd2.vehicleId, rnd1.currentLocation, true)

    case TriggerWithId(RepositioningTimer(tick),triggerId) =>  {

    //print()
      // TODO: add initial timer

      // TODO: reposition vehicles

      //  beamServices.schedulerRef ! RepositioningTimer -> make trigger

      // get two random idling TNCs
      // move one TNC to the other.
      val rnd = new Random
      val availableKeyset = availableRideHailVehicles.keySet.toArray
      implicit val timeout: Timeout = Timeout(50000, TimeUnit.SECONDS)
      import context.dispatcher
      if(availableKeyset.size > 1) {
        val idRnd1 = availableKeyset.apply(rnd.nextInt(availableKeyset.size))
        val idRnd2 = availableKeyset
          .filterNot(_.equals(idRnd1))
          .apply(rnd.nextInt(availableKeyset.size - 1))

        for{
          rnd1 <- availableRideHailVehicles.get(idRnd1)
          rnd2 <- availableRideHailVehicles.get(idRnd2)
        } yield {
          val departureTime: BeamTime = DiscreteTime(0)
          val futureRnd1AgentResponse = router ? RoutingRequest(
            rnd1.currentLocation.loc, rnd2.currentLocation.loc, departureTime, Vector(), Vector(),
            createdAt = ZonedDateTime.now(ZoneOffset.UTC)) //TODO what should go in vectors
          val rnd1SentTime = System.currentTimeMillis()
          RoutingRequestSenderCounter.sent()

          log.info(s"TriggerWithId. Sent first random RoutingRequest") // get route from customer to destination
          val futureRnd2AgentResponse  = router ? RoutingRequest(
            rnd2.currentLocation.loc, rnd1.currentLocation.loc, departureTime, Vector(), Vector(),
            createdAt = ZonedDateTime.now(ZoneOffset.UTC)) //TODO what should go in vectors
          val rnd2SentTime = System.currentTimeMillis()
          RoutingRequestSenderCounter.sent()

          log.info(s"TriggerWithId. Sent second random RoutingRequest")

          for{
            rnd1Response <- futureRnd1AgentResponse.mapTo[RoutingResponse]
              .map { r => r.copy(receivedAt = Some(ZonedDateTime.now(ZoneOffset.UTC)))}
            rnd1ReceiveTime = System.currentTimeMillis()
            rnd2Response <- futureRnd2AgentResponse.mapTo[RoutingResponse]
              .map { r => r.copy(receivedAt = Some(ZonedDateTime.now(ZoneOffset.UTC)))}
            rnd2ReceiveTime = System.currentTimeMillis()
          } yield {
            val rnd1Dt = rnd1ReceiveTime - rnd1SentTime
            val rnd2Dt = rnd2ReceiveTime - rnd2SentTime
            log.info("TriggerWithId. rnd1Dt: {} ms, rnd2Dt: {} ms", rnd1Dt, rnd2Dt)
            self ! RepositionResponse(rnd1, rnd2, rnd1Response, rnd2Response)
          }
        }
      }

//      updateLocationOfAgent(rnd1, )
      // TODO: schedule next Timer
      // start relocate?

      // end relocate?


      val timerTrigger=RepositioningTimer(tick+selfTimerTimoutDuration)
      val timerMessage=ScheduleTrigger(timerTrigger, self)
      scheduler ! timerMessage
      scheduler ! CompletionNotice(triggerId)
    }


    case CheckOutResource(_) =>
      // Because the RideHail Manager is in charge of deciding which specific vehicles to assign to customers, this should never be used
      throw new RuntimeException("Illegal use of CheckOutResource, RideHailingManager is responsible for checking out vehicles in fleet.")


    case RideHailingInquiry(inquiryId, personId, customerPickUp, departAt, destination) =>
      implicit val ex = executionContext
      val customerAgent = sender()
      getClosestRideHailingAgent(customerPickUp, radius) match {
        case Some((rideHailingLocation, shortDistanceToRideHailingAgent)) =>
          lockedVehicles += rideHailingLocation.vehicleId

          // Need to have this dispatcher here for the future execution below
          // import context.dispatcher

          val (futureRideHailingAgent2CustomerResponse, futureRideHailing2DestinationResponse) =
            createCustomerInquiryResponse(personId, customerPickUp, departAt, destination, rideHailingLocation)
          val sentTime = System.currentTimeMillis()
          for {
            rideHailingAgent2CustomerResponse <- futureRideHailingAgent2CustomerResponse.mapTo[RoutingResponse]
                .map { r => r.copy(receivedAt = Some(ZonedDateTime.now(ZoneOffset.UTC)))}
            customerRespReceiveTime = System.currentTimeMillis()
            rideHailing2DestinationResponse <- futureRideHailing2DestinationResponse.mapTo[RoutingResponse]
              .map { r => r.copy(receivedAt = Some(ZonedDateTime.now(ZoneOffset.UTC)))}
            destinationRespReceiveTime = System.currentTimeMillis()
          } {
            val custDt = customerRespReceiveTime - sentTime
            val destDt = destinationRespReceiveTime - sentTime

            log.info("RideHailingInquiry. CustomerResponse: {} ms, DestinationResponse: {} ms", custDt, destDt)

            log.info("RideHailingInquiry. Customer Full time: {} ms", ChronoUnit.MILLIS.between(rideHailingAgent2CustomerResponse.requestCreatedAt, rideHailingAgent2CustomerResponse.receivedAt.get))
            log.info("RideHailingInquiry. Customer RoutingRequest travel time: {} ms", ChronoUnit.MILLIS.between(rideHailingAgent2CustomerResponse.requestCreatedAt, rideHailingAgent2CustomerResponse.requestReceivedAt))
            log.info("RideHailingInquiry. Customer RoutingResponse travel time: {} ms", ChronoUnit.MILLIS.between(rideHailingAgent2CustomerResponse.createdAt, rideHailingAgent2CustomerResponse.receivedAt.get))

            log.info("RideHailingInquiry. Destination Full time: {} ms", ChronoUnit.MILLIS.between(rideHailing2DestinationResponse.requestCreatedAt, rideHailing2DestinationResponse.receivedAt.get))
            log.info("RideHailingInquiry. Destination RoutingRequest travel time: {} ms", ChronoUnit.MILLIS.between(rideHailing2DestinationResponse.requestCreatedAt, rideHailing2DestinationResponse.requestReceivedAt))
            log.info("RideHailingInquiry. Destination RoutingResponse travel time: {} ms", ChronoUnit.MILLIS.between(rideHailing2DestinationResponse.createdAt, rideHailing2DestinationResponse.receivedAt.get))

            RoutingRequestResponseStats.add(rideHailingAgent2CustomerResponse)
            RoutingRequestResponseStats.add(rideHailing2DestinationResponse)

            // TODO: could we just call the code, instead of sending the message here?
            self ! RoutingResponses(customerAgent, inquiryId, personId, customerPickUp,departAt, rideHailingLocation, shortDistanceToRideHailingAgent, rideHailingAgent2CustomerResponse, rideHailing2DestinationResponse)
          }
        case None =>
          // no rides to hail
          customerAgent ! RideHailingInquiryResponse(inquiryId, Vector(), error = Option(CouldNotFindRouteToCustomer))
      }

    case RoutingResponses(customerAgent, inquiryId, personId, customerPickUp,departAt, rideHailingLocation, shortDistanceToRideHailingAgent, rideHailingAgent2CustomerResponse, rideHailing2DestinationResponse) =>
      val timesToCustomer: Seq[Long] = rideHailingAgent2CustomerResponse.itineraries.map(t => t.totalTravelTime)
      // TODO: Find better way of doing this error checking than sentry value
      val timeToCustomer = if (timesToCustomer.nonEmpty) {
        timesToCustomer.min
      } else Long.MaxValue
      // TODO: Do unit conversion elsewhere... use squants or homegrown unit conversions, but enforce
      val rideHailingFare = DefaultCostPerMinute / 60.0 * surgePricingManager.getSurgeLevel(customerPickUp,departAt.atTime.toDouble)

      val customerPlans2Costs: Map[RoutingModel.EmbodiedBeamTrip, BigDecimal] = rideHailing2DestinationResponse.itineraries.map(t => (t, rideHailingFare * t.totalTravelTime)).toMap
      val itins2Cust = rideHailingAgent2CustomerResponse.itineraries.filter(x => x.tripClassifier.equals(RIDE_HAIL))
      val itins2Dest = rideHailing2DestinationResponse.itineraries.filter(x => x.tripClassifier.equals(RIDE_HAIL))
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

        val rideHailingAgent2CustomerResponseMod = RoutingResponse(modRHA2Cust, requestCreatedAt = rideHailingAgent2CustomerResponse.requestCreatedAt,
          requestReceivedAt = rideHailingAgent2CustomerResponse.requestReceivedAt, createdAt = rideHailingAgent2CustomerResponse.createdAt,
          receivedAt = rideHailingAgent2CustomerResponse.receivedAt)
        val rideHailing2DestinationResponseMod = RoutingResponse(modRHA2Dest,  requestCreatedAt = rideHailing2DestinationResponse.requestCreatedAt,
          requestReceivedAt = rideHailing2DestinationResponse.requestReceivedAt, createdAt = rideHailing2DestinationResponse.createdAt,
          receivedAt = rideHailing2DestinationResponse.receivedAt)

        val travelProposal = TravelProposal(rideHailingLocation, timeToCustomer, cost, Option(FiniteDuration
        (customerTripPlan.totalTravelTime, TimeUnit.SECONDS)), rideHailingAgent2CustomerResponseMod,
          rideHailing2DestinationResponseMod)
        pendingInquiries.put(inquiryId, (travelProposal, modRHA2Dest.head.toBeamTrip()))
        log.debug(s"Found ride to hail for  person=$personId and inquiryId=$inquiryId within " +
          s"$shortDistanceToRideHailingAgent meters, timeToCustomer=$timeToCustomer seconds and cost=$$$cost")
        customerAgent ! RideHailingInquiryResponse(inquiryId, Vector(travelProposal))
      } else {
        log.debug(s"Router could not find route to customer person=$personId for inquiryId=$inquiryId")
        lockedVehicles -= rideHailingLocation.vehicleId

        customerAgent ! RideHailingInquiryResponse(inquiryId, Vector(), error = Option(CouldNotFindRouteToCustomer))
      }

    case ReserveRide(inquiryId, vehiclePersonIds, customerPickUp, departAt, destination) =>
      if (pendingInquiries.asMap.containsKey(inquiryId)) {
        val (travelPlanOpt: Option[(TravelProposal, BeamTrip)], customerAgent: ActorRef, closestRHA: Option[RideHailingAgentLocation]) = findClosestRideHailingAgents(inquiryId, customerPickUp)

        closestRHA match {
          case Some((closestRideHailingAgent)) =>
            val travelProposal = travelPlanOpt.get._1
            surgePricingManager.addRideCost(departAt.atTime, travelProposal.estimatedPrice.doubleValue(),customerPickUp)


            val tripPlan = travelPlanOpt.map(_._2)
            handleReservation(inquiryId, vehiclePersonIds, customerPickUp, destination, customerAgent,
              closestRideHailingAgent, travelProposal, tripPlan)
          // We have an agent nearby, but it's not the one we originally wanted
          case _ =>
            customerAgent ! ReservationResponse(Id.create(inquiryId.toString, classOf[ReservationRequest]), Left
            (UnknownRideHailReservationError))
        }
      } else {
        sender() ! ReservationResponse(Id.create(inquiryId.toString, classOf[ReservationRequest]), Left
        (UnknownInquiryIdError))
      }
    case ModifyPassengerScheduleAck(inquiryIDOption, triggersToSchedule) =>
      completeReservation(Id.create(inquiryIDOption.get.toString, classOf[RideHailingInquiry]), triggersToSchedule)

    case ReleaseVehicleReservation(_, vehId) =>
      lockedVehicles -= vehId

    case Finish =>
      log.info("finish message received from BeamAgentScheduler")

    case msg =>
      log.warning(s"unknown message received by RideHailingManager $msg from ${sender().path.toString()}")

  }

  private def findClosestRideHailingAgents(inquiryId: Id[RideHailingInquiry], customerPickUp: Location) = {

    val travelPlanOpt = Option(pendingInquiries.asMap.remove(inquiryId))
    val customerAgent = sender()
    /**
      * 1. customerAgent ! ReserveRideConfirmation(availableRideHailingAgentSpatialIndex, customerId, travelProposal)
      * 2. availableRideHailingAgentSpatialIndex ! PickupCustomer
      */
    val nearbyRideHailingAgents = availableRideHailingAgentSpatialIndex.getDisk(customerPickUp.getX, customerPickUp.getY,
      radius).asScala.toVector
    val closestRHA: Option[RideHailingAgentLocation] = nearbyRideHailingAgents.filter(x =>
      lockedVehicles(x.vehicleId)).find(_.vehicleId.equals(travelPlanOpt.get._1.responseRideHailing2Pickup
      .itineraries.head.vehiclesInTrip.head))
    (travelPlanOpt, customerAgent, closestRHA)
  }

  private def createCustomerInquiryResponse(personId: Id[PersonAgent], customerPickUp: Location, departAt: BeamTime, destination: Location, rideHailingLocation: RideHailingAgentLocation): (Future[Any], Future[Any]) = {
    val customerAgentBody = StreetVehicle(Id.createVehicleId(s"body-$personId"), SpaceTime((customerPickUp,
      departAt.atTime)), WALK, asDriver = true)
    val rideHailingVehicleAtOrigin = StreetVehicle(rideHailingLocation.vehicleId, SpaceTime(
      (rideHailingLocation.currentLocation.loc, departAt.atTime)), CAR, asDriver = false)
    val rideHailingVehicleAtPickup = StreetVehicle(rideHailingLocation.vehicleId, SpaceTime((customerPickUp,
      departAt.atTime)), CAR, asDriver = false)

    //TODO: Error handling. In the (unlikely) event of a timeout, this RideHailingManager will silently be
    //TODO: restarted, and probably someone will wait forever for its reply.
    implicit val timeout: Timeout = Timeout(50000, TimeUnit.SECONDS)

    // get route from ride hailing vehicle to customer
    val futureRideHailingAgent2CustomerResponse = router ? RoutingRequest(rideHailingLocation
          .currentLocation.loc, customerPickUp, departAt, Vector(), Vector(rideHailingVehicleAtOrigin),
      createdAt = ZonedDateTime.now(ZoneOffset.UTC))

    RoutingRequestSenderCounter.sent()

    //XXXX: customer trip request might be redundant... possibly pass in info

    // get route from customer to destination
    val futureRideHailing2DestinationResponse = router ? RoutingRequest(customerPickUp, destination, departAt, Vector(),
      Vector(customerAgentBody, rideHailingVehicleAtPickup), createdAt = ZonedDateTime.now(ZoneOffset.UTC))

    RoutingRequestSenderCounter.sent()

    (futureRideHailingAgent2CustomerResponse, futureRideHailing2DestinationResponse)
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

  private def handleReservation(inquiryId: Id[RideHailingInquiry], vehiclePersonId: VehiclePersonId,
                                customerPickUp: Location, destination: Location,
                                customerAgent: ActorRef, closestRideHailingAgentLocation: RideHailingAgentLocation,
                                travelProposal: TravelProposal, trip2DestPlan: Option[BeamTrip]): Unit = {

    // Modify RH agent passenger schedule and create BeamAgentScheduler message that will dispatch RH agent to do the
    // pickup
    val passengerSchedule = PassengerSchedule()
      .addLegs(travelProposal.responseRideHailing2Pickup.itineraries.head.toBeamTrip.legs) // Adds empty trip to customer
      .addPassenger(vehiclePersonId, trip2DestPlan.get.legs.filter(_.mode == CAR)) // Adds customer's actual trip to destination
    putIntoService(closestRideHailingAgentLocation)
    lockedVehicles -= closestRideHailingAgentLocation.vehicleId

    // Create confirmation info but stash until we receive ModifyPassengerScheduleAck
    pendingModifyPassengerScheduleAcks.put(inquiryId, ReservationResponse(Id.create(inquiryId.toString,
      classOf[ReservationRequest]), Right(ReserveConfirmInfo(trip2DestPlan.head.legs.head, trip2DestPlan.last.legs
      .last, vehiclePersonId, Vector()))))
    closestRideHailingAgentLocation.rideHailAgent ! ModifyPassengerSchedule(passengerSchedule, Some(inquiryId))
  }

  private def completeReservation(inquiryId: Id[RideHailingInquiry], triggersToSchedule: Seq[ScheduleTrigger]): Unit = {
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

  private def getClosestRideHailingAgent(pickupLocation: Coord, radius: Double): Option[(RideHailingAgentLocation,
    Double)] = {
    val nearbyRideHailingAgents = availableRideHailingAgentSpatialIndex.getDisk(pickupLocation.getX, pickupLocation.getY,
      radius).asScala.toVector
    val distances2RideHailingAgents = nearbyRideHailingAgents.map(rideHailingAgentLocation => {
      val distance = CoordUtils.calcProjectedEuclideanDistance(pickupLocation, rideHailingAgentLocation
        .currentLocation.loc)
      (rideHailingAgentLocation, distance)
    })
    //TODO: Possibly get multiple taxis in this block
    distances2RideHailingAgents.filterNot(x => lockedVehicles(x._1.vehicleId)).sortBy(_._2).headOption
  }


}

/**
  * BEAM
  */





object RideHailingManager {
  val RIDE_HAIL_MANAGER = "RideHailingManager"

  def nextRideHailingInquiryId: Id[RideHailingInquiry] = Id.create(UUIDGen.createTime(UUIDGen.newTime()).toString,
    classOf[RideHailingInquiry])

  case class NotifyIterationEnds()

  case class RideHailingInquiry(inquiryId: Id[RideHailingInquiry], customerId: Id[PersonAgent],
                                pickUpLocation: Location, departAt: BeamTime, destination: Location)

  case class TravelProposal(rideHailingAgentLocation: RideHailingAgentLocation, timesToCustomer: Long,
                            estimatedPrice: BigDecimal, estimatedTravelTime: Option[Duration],
                            responseRideHailing2Pickup: RoutingResponse, responseRideHailing2Dest: RoutingResponse)

  case class RideHailingInquiryResponse(inquiryId: Id[RideHailingInquiry], proposals: Seq[TravelProposal],
                                        error: Option[ReservationError] = None)

  case class ReserveRide(inquiryId: Id[RideHailingInquiry], customerIds: VehiclePersonId, pickUpLocation: Location,
                         departAt: BeamTime, destination: Location)

  private case class RoutingResponses(customerAgent: ActorRef, inquiryId: Id[RideHailingInquiry],
                                      personId: Id[PersonAgent], customerPickUp: Location,departAt:BeamTime, rideHailingLocation: RideHailingAgentLocation,
                                      shortDistanceToRideHailingAgent: Double,
                                      rideHailingAgent2CustomerResponse: RoutingResponse,
                                      rideHailing2DestinationResponse: RoutingResponse)

  case class ReserveRideResponse(inquiryId: Id[RideHailingInquiry], data: Either[ReservationError, RideHailConfirmData])

  case class RideHailConfirmData(rideHailAgent: ActorRef, customerId: Id[PersonAgent], travelProposal: TravelProposal)

  case class RegisterRideAvailable(rideHailingAgent: ActorRef, vehicleId: Id[Vehicle], availableSince: SpaceTime)

  case class RegisterRideUnavailable(ref: ActorRef, location: Coord)

  case class RideHailingAgentLocation(rideHailAgent: ActorRef, vehicleId: Id[Vehicle], currentLocation: SpaceTime)

  case object RideUnavailableAck

  case object RideAvailableAck

  case class RepositioningTimer(tick: Double) extends Trigger

  case class RepositionResponse(rnd1: RideHailingAgentLocation, rnd2: RideHailingManager.RideHailingAgentLocation,
                                rnd1Response: RoutingResponse, rnd2Response: RoutingResponse)


  def props(services: BeamServices, scheduler: ActorRef, router: ActorRef, boundingBox: Envelope, surgePricingManager: RideHailSurgePricingManager) = {
    Props(new RideHailingManager(services, scheduler, router, boundingBox, surgePricingManager))
  }
}