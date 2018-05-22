package beam.agentsim.agents.rideHail

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
import beam.agentsim.agents.rideHail.RideHailingAgent._
import beam.agentsim.agents.rideHail.RideHailingManager._
import beam.agentsim.agents.rideHail.allocationManagers._
import beam.agentsim.agents.vehicles.AccessErrorCodes.{CouldNotFindRouteToCustomer, RideHailVehicleTakenError, UnknownInquiryIdError, UnknownRideHailReservationError}
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.agents.vehicles._
import beam.agentsim.events.SpaceTime
import beam.agentsim.events.resources.ReservationError
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.{Trigger, TriggerWithId}
import beam.router.BeamRouter._
import beam.router.Modes.BeamMode._
import beam.router.RoutingModel
import beam.router.RoutingModel.{BeamTime, BeamTrip, DiscreteTime}
import beam.sim.{BeamServices, HasServices}
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
import scala.collection.{concurrent, mutable}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.{Duration, FiniteDuration}

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

  override val resources: mutable.Map[Id[BeamVehicle], BeamVehicle] = mutable.Map[Id[BeamVehicle], BeamVehicle]()

  private val vehicleFuelLevel: mutable.Map[Id[Vehicle], Double] = mutable.Map[Id[Vehicle], Double]()

  private val DefaultCostPerMinute = BigDecimal(beamServices.beamConfig.beam.agentsim.agents.rideHailing.defaultCostPerMinute)
  private val DefaultCostPerSecond = DefaultCostPerMinute / 60.0d

  private val rideHailAllocationManagerTimeoutInSeconds = {
    beamServices.beamConfig.beam.agentsim.agents.rideHailing.rideHailAllocationManagerTimeoutInSeconds
  }

  val allocationManager: String = beamServices.beamConfig.beam.agentsim.agents.rideHailing.allocationManager

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

  private val passengerScheduleOverrideRequests = mutable.Map[Id[Vehicle], PassengerSchedule]()

  private val repositionDoneOnce: Boolean = false

  private var maybeTravelTime: Option[TravelTime] = None

  private var bufferedReserveRideMessages = mutable.Map[Id[RideHailingInquiry], ReserveRide]()

  private val handleRideHailInquirySubmitted = mutable.Set[Id[RideHailingInquiry]]()

  var nextCompleteNoticeRideHailAllocationTimeout: CompletionNotice = _

  beamServices.beamRouter ! GetTravelTime
  beamServices.beamRouter ! GetMatSimNetwork

  var transportNetwork: Option[TransportNetwork] = None
  var matsimNetwork: Option[Network] = None


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
  lazy val pendingInquiries: Cache[Id[RideHailingInquiry], (TravelProposal, BeamTrip)] = {
    CacheBuilder.newBuilder()
      .expireAfterWrite(10, TimeUnit.SECONDS)
      .build()
  }

  private val pendingModifyPassengerScheduleAcks = collection.concurrent.TrieMap[Id[RideHailingInquiry],
    ReservationResponse]()
  private var lockedVehicles = Set[Id[Vehicle]]()

  override def receive: Receive = {
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
        val rideHailingAgentLocation = RideHailingAgentLocation(driver, vehicleId, availableIn.get)
        makeAvailable(rideHailingAgentLocation)
        sender ! CheckInSuccess
      })


    case BeamVehicleFuelLevelUpdate(id, fuelLevel) =>
      vehicleFuelLevel.put(id, fuelLevel)

    case Finish =>
      log.info("received Finish message")

    case MATSimNetwork(network) =>
      matsimNetwork = Some(network)

    case CheckOutResource(_) =>
      // Because the RideHail Manager is in charge of deciding which specific vehicles to assign to customers, this should never be used
      throw new RuntimeException("Illegal use of CheckOutResource, RideHailingManager is responsible for checking out vehicles in fleet.")


    case inquiry@RideHailingInquiry(inquiryId, personId, customerPickUp, departAt, destination) =>
      val customerAgent = sender()
      var rideHailLocationAndShortDistance: Option[(RideHailingAgentLocation, Double)] = None

      val vehicleAllocationRequest = VehicleAllocationRequest(customerPickUp, departAt, destination, isInquiry = true)

      val vehicleAllocation = rideHailResourceAllocationManager.proposeVehicleAllocation(vehicleAllocationRequest)

      vehicleAllocation match {
        case Some(allocation) =>
          // TODO (RW): Test following code with stanford class
          val rideHailAgent = resources.get(agentsim.vehicleId2BeamVehicleId(allocation.vehicleId)).orElse(beamServices.vehicles.get(allocation.vehicleId)).get.driver.head
          val rideHailingAgentLocation = RideHailingAgentLocation(rideHailAgent, allocation.vehicleId, allocation.availableAt)
          val distance = CoordUtils.calcProjectedEuclideanDistance(customerPickUp, rideHailingAgentLocation.currentLocation.loc)
          rideHailLocationAndShortDistance = Some(rideHailingAgentLocation, distance)


        case None =>
          // use default allocation manager
          rideHailLocationAndShortDistance = getClosestIdleRideHailingAgent(customerPickUp, radiusInMeters)
      }

      handleRideHailInquiry(inquiryId, personId, customerPickUp, departAt, destination, rideHailLocationAndShortDistance, Some(customerAgent))

    case R5Network(network) =>
      this.transportNetwork = Some(network)

    case RoutingResponses(customerAgent,
    inquiryId,
    personId,
    customerPickUp,
    departAt,
    rideHailingLocation,
    shortDistanceToRideHailingAgent,
    rideHailingAgent2CustomerResponse,
    rideHailing2DestinationResponse) =>
      val timesToCustomer: Vector[Long] = rideHailingAgent2CustomerResponse.itineraries.map(t => t.totalTravelTimeInSecs)

      // TODO: Find better way of doing this error checking than sentry value
      val timeToCustomer = if (timesToCustomer.isEmpty) Long.MaxValue else timesToCustomer.min

      // TODO: Do unit conversion elsewhere... use squants or homegrown unit conversions, but enforce
      val rideHailingFarePerSecond = DefaultCostPerSecond * surgePricingManager.getSurgeLevel(customerPickUp, departAt.atTime.toDouble)

      val customerPlans2Costs: Map[RoutingModel.EmbodiedBeamTrip, BigDecimal] = rideHailing2DestinationResponse.itineraries.map(t => (t, rideHailingFarePerSecond * t.totalTravelTimeInSecs)).toMap
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

        val rideHailingAgent2CustomerResponseMod = RoutingResponse(modRHA2Cust)
        val rideHailing2DestinationResponseMod = RoutingResponse(modRHA2Dest)

        val travelProposal = TravelProposal(rideHailingLocation, timeToCustomer, cost, Option(FiniteDuration
        (customerTripPlan.totalTravelTimeInSecs, TimeUnit.SECONDS)), rideHailingAgent2CustomerResponseMod,
          rideHailing2DestinationResponseMod)
        pendingInquiries.put(inquiryId, (travelProposal, modRHA2Dest.head.toBeamTrip()))
        log.debug(s"Found ride to hail for  person=$personId and inquiryId=$inquiryId within " +
          s"$shortDistanceToRideHailingAgent meters, timeToCustomer=$timeToCustomer seconds and cost=$$$cost")


        customerAgent match {
          case Some(customerActor) => customerActor ! RideHailingInquiryResponse(inquiryId, Vector(travelProposal))
          case None =>

            bufferedReserveRideMessages.get(inquiryId) match {
              case Some(ReserveRide(localInquiryId, vehiclePersonIds, localCustomerPickUp, localDepartAt, destination)) =>
                handlePendingQuery(localInquiryId, vehiclePersonIds, localCustomerPickUp, localDepartAt, destination)
                bufferedReserveRideMessages.remove(localInquiryId)
                handleRideHailInquirySubmitted.remove(localInquiryId)

                // TODO if there is any issue related to bufferedReserveRideMessages, look at this closer (e.g. make two data structures, one for those
                // before timout and one after
                if (handleRideHailInquirySubmitted.size == 0) {
                  sendoutAckMessageToSchedulerForRideHailAllocationmanagerTimeout()
                }

              case _ =>
            }
          // call was made by ride hail agent itself


        }

      } else {
        log.debug(s"Router could not find route to customer person=$personId for inquiryId=$inquiryId")
        lockedVehicles -= rideHailingLocation.vehicleId


        customerAgent match {
          case Some(customerAgent) => customerAgent ! RideHailingInquiryResponse(inquiryId, Vector(), error = Option(CouldNotFindRouteToCustomer))
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


    case modifyPassengerScheduleAck@ModifyPassengerScheduleAck(inquiryIDOption, triggersToSchedule) =>

      if (inquiryIDOption.isEmpty) {
        val newTriggers = triggersToSchedule ++ nextCompleteNoticeRideHailAllocationTimeout.newTriggers

        scheduler ! CompletionNotice(nextCompleteNoticeRideHailAllocationTimeout.id, newTriggers)

        DebugLib.emptyFunctionForSettingBreakPoint()

      } else {
        completeReservation(Id.create(inquiryIDOption.get.toString, classOf[RideHailingInquiry]), triggersToSchedule)
      }

    case ReleaseVehicleReservation(_, vehId) =>
      lockedVehicles -= vehId

    // TODO (RW): this needs to be called according to timeout settings

    case UpdateTravelTime(travelTime) =>
      maybeTravelTime = Some(travelTime)

    case TriggerWithId(RideHailAllocationManagerTimeout(tick), triggerId) => {
      prepareAckMessageToSchedulerForRideHailAllocationManagerTimeout(tick, triggerId)

      if (repositionDoneOnce) {
        sendoutAckMessageToSchedulerForRideHailAllocationmanagerTimeout()
      } else {
        val repositionVehicles: Vector[(Id[Vehicle], Location)] = rideHailResourceAllocationManager.repositionVehicles(tick)

        if (repositionVehicles.isEmpty) {
          sendoutAckMessageToSchedulerForRideHailAllocationmanagerTimeout()
        }

        for (repositionVehicle <- repositionVehicles) {

          val (vehicleId, destinationLocation) = repositionVehicle

          if (getIdleVehicles().contains(vehicleId)) {
            val rideHailAgentLocation = getIdleVehicles().get(vehicleId).get

            println("RHM: tick(" + tick + ")" + vehicleId + " - " + rideHailAgentLocation.currentLocation.loc + " -> " + destinationLocation)

            val rideHailAgent = rideHailAgentLocation.rideHailAgent

            val rideHailingVehicleAtOrigin = StreetVehicle(rideHailAgentLocation.vehicleId, SpaceTime(
              (rideHailAgentLocation.currentLocation.loc, tick.toLong)), CAR, asDriver = false)

            val departAt = DiscreteTime(tick.toInt)

            implicit val timeout: Timeout = Timeout(50000, TimeUnit.SECONDS)

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

                val passengerSchedule = PassengerSchedule().addLegs(rideHailingAgent2CustomerResponseMod.itineraries.head.toBeamTrip.legs)

                passengerScheduleOverrideRequests.put(vehicleId, passengerSchedule)
                rideHailAgent ! Interrupt()

              } else {
                sendoutAckMessageToSchedulerForRideHailAllocationmanagerTimeout()
                //println("NO repositioning done")
              }
            }
          }
        }


      }

    }

    case InterruptedWhileIdle(vehicleId) =>
      println("A")
      val rideHailAgentLocation = getIdleVehicles().get(vehicleId).get
      println("B")
      val rideHailAgent = rideHailAgentLocation.rideHailAgent
      val passengerSchedule = passengerScheduleOverrideRequests.remove(vehicleId).get
      println("C")

      rideHailAgent ! ModifyPassengerSchedule(passengerSchedule)
      rideHailAgent ! Resume()

    // Response to Interrupt() from RideHailingAgent. We don't care about it for now.

    case InterruptedAt(_, _, vehicleId) =>
      println("D")
      val rideHailAgentLocation = getIdleVehicles().get(vehicleId).get
      val rideHailAgent = rideHailAgentLocation.rideHailAgent
      println("E")
      rideHailAgent ! StopDriving()
      val passengerSchedule = passengerScheduleOverrideRequests.remove(vehicleId).get
      println("F")
      rideHailAgent ! ModifyPassengerSchedule(passengerSchedule)
      rideHailAgent ! Resume()

    // Response to Interrupt() from DrivesVehicle. We don't care about it for now.

    case Finish =>
      log.info("finish message received from BeamAgentScheduler")

    case msg =>
      log.warning(s"unknown message received by RideHailingManager $msg")

  }


  private def prepareAckMessageToSchedulerForRideHailAllocationManagerTimeout(tick: Double, triggerId: Long): Unit = {
    val timerTrigger = RideHailAllocationManagerTimeout(tick + rideHailAllocationManagerTimeoutInSeconds)
    val timerMessage = ScheduleTrigger(timerTrigger, self)
    nextCompleteNoticeRideHailAllocationTimeout = CompletionNotice(triggerId, Vector(timerMessage))


  }

  private def sendoutAckMessageToSchedulerForRideHailAllocationmanagerTimeout(): Unit = {
    scheduler ! nextCompleteNoticeRideHailAllocationTimeout
  }

  private def findClosestRideHailingAgents(inquiryId: Id[RideHailingInquiry], customerPickUp: Location) = {

    val travelPlanOpt = Option(pendingInquiries.asMap.remove(inquiryId))
    val customerAgent = sender()
    /**
      * 1. customerAgent ! ReserveRideConfirmation(availableRideHailingAgentSpatialIndex, customerId, travelProposal)
      * 2. availableRideHailingAgentSpatialIndex ! PickupCustomer
      */
    val nearbyRideHailingAgents = availableRideHailingAgentSpatialIndex.getDisk(customerPickUp.getX, customerPickUp.getY,
      radiusInMeters).asScala.toVector
    val closestRHA: Option[RideHailingAgentLocation] = nearbyRideHailingAgents.filter(x =>
      lockedVehicles(x.vehicleId)).find(_.vehicleId.equals(travelPlanOpt.get._1.responseRideHailing2Pickup
      .itineraries.head.vehiclesInTrip.head))
    (travelPlanOpt, customerAgent, closestRHA)
  }

  private def createCustomerInquiryResponse(personId: Id[PersonAgent],
                                            customerPickUp: Location,
                                            departAt: BeamTime,
                                            destination: Location,
                                            rideHailingLocation: RideHailingAgentLocation): (Future[Any], Future[Any]) = {
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
      .currentLocation.loc, customerPickUp, departAt, Vector(), Vector(rideHailingVehicleAtOrigin))
    //XXXX: customer trip request might be redundant... possibly pass in info

    // get route from customer to destination
    val futureRideHailing2DestinationResponse = router ? RoutingRequest(customerPickUp, destination, departAt, Vector(), Vector(customerAgentBody, rideHailingVehicleAtPickup))
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
      case Some(links) => Some(links.get(Id.createLinkId(linkId.toString)).asInstanceOf[Link].getFreespeed)
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

    val iterator = set.iterator
    var linkIdVector: Vector[Int] = Vector()
    while (iterator.hasNext) {
      val linkId: Id[Link] = iterator.next()
      val _linkId = linkId.toString.toInt
      linkIdVector = linkIdVector :+ _linkId
    }

    linkIdVector
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
    closestRideHailingAgentLocation.rideHailAgent ! Interrupt()
    // RideHailingAgent sends reply which we are ignoring here,
    // but that's okay, we don't _need_ to wait for the reply if the answer doesn't interest us.
    closestRideHailingAgentLocation.rideHailAgent ! ModifyPassengerSchedule(passengerSchedule, Some(inquiryId))
    closestRideHailingAgentLocation.rideHailAgent ! Resume()
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


  def getClosestIdleVehiclesWithinRadius(pickupLocation: Coord, radius: Double): Vector[(RideHailingAgentLocation,
    Double)] = {
    val nearbyRideHailingAgents = availableRideHailingAgentSpatialIndex.getDisk(pickupLocation.getX, pickupLocation.getY,
      radius).asScala.toVector
    val distances2RideHailingAgents = nearbyRideHailingAgents.map(rideHailingAgentLocation => {
      val distance = CoordUtils.calcProjectedEuclideanDistance(pickupLocation, rideHailingAgentLocation
        .currentLocation.loc)
      (rideHailingAgentLocation, distance)
    })
    //TODO: Possibly get multiple taxis in this block
    distances2RideHailingAgents.filterNot(x => lockedVehicles(x._1.vehicleId)).sortBy(_._2)
  }

  def getClosestIdleRideHailingAgent(pickupLocation: Coord,
                                     radius: Double): Option[(RideHailingAgentLocation, Double)] = {
    getClosestIdleVehiclesWithinRadius(pickupLocation, radius).headOption
  }


  private def handlePendingQuery(inquiryId: Id[RideHailingInquiry], vehiclePersonIds: VehiclePersonId, customerPickUp: Location,
                                 departAt: BeamTime, destination: Location): Unit = {
    if (pendingInquiries.asMap.containsKey(inquiryId)) {
      val (travelPlanOpt: Option[(TravelProposal, BeamTrip)], customerAgent: ActorRef, closestRHA: Option[RideHailingAgentLocation]) = findClosestRideHailingAgents(inquiryId, customerPickUp)

      closestRHA match {
        case Some(closestRideHailingAgent) =>
          val travelProposal = travelPlanOpt.get._1
          surgePricingManager.addRideCost(departAt.atTime, travelProposal.estimatedPrice.doubleValue(), customerPickUp)


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
  }

  def lockVehicle(vehicleId: Id[Vehicle]) = {
    lockedVehicles += vehicleId
  }


  def getVehicleFuelLevel(vehicleId: Id[Vehicle]): Double = {
    vehicleFuelLevel.get(vehicleId).get
  }

  def getIdleVehicles(): collection.concurrent.TrieMap[Id[Vehicle], RideHailingAgentLocation] = {
    availableRideHailVehicles
  }


  private def handleRideHailInquiry(inquiryId: Id[RideHailingInquiry], personId: Id[PersonAgent],
                                    customerPickUp: Location, departAt: BeamTime, destination: Location, rideHailLocationAndShortDistance: Option[(RideHailingAgentLocation, Double)], customerAgent: Option[ActorRef]): Unit = {
    rideHailLocationAndShortDistance match {
      case Some((rideHailingLocation, shortDistanceToRideHailingAgent)) =>
        if (!rideHailResourceAllocationManager.isBufferedRideHailAllocationMode) {
          // only lock vehicle in immediate processing mode, in buffered processing mode we lock vehicle only when batch queries are beeing processing
          lockedVehicles += rideHailingLocation.vehicleId
        }

        // Need to have this dispatcher here for the future execution below

        val (futureRideHailingAgent2CustomerResponse, futureRideHailing2DestinationResponse) =
          createCustomerInquiryResponse(personId, customerPickUp, departAt, destination, rideHailingLocation)

        for {
          rideHailingAgent2CustomerResponse <- futureRideHailingAgent2CustomerResponse.mapTo[RoutingResponse]
          rideHailing2DestinationResponse <- futureRideHailing2DestinationResponse.mapTo[RoutingResponse]
        } {
          // TODO: could we just call the code, instead of sending the message here?
          self ! RoutingResponses(customerAgent, inquiryId, personId, customerPickUp, departAt, rideHailingLocation, shortDistanceToRideHailingAgent, rideHailingAgent2CustomerResponse, rideHailing2DestinationResponse)
        }
      case None =>
        // no rides to hail
        customerAgent match {
          case Some(localCustomerAgent) =>
            localCustomerAgent ! RideHailingInquiryResponse(inquiryId, Vector(), error = Option(CouldNotFindRouteToCustomer))
          case None =>
            self ! RideHailingInquiryResponse(inquiryId, Vector(), error = Option(CouldNotFindRouteToCustomer))
          // TODO RW: handle this error case where RideHailingInquiryResponse is sent back to us
        }
    }
  }


}

object RideHailingManager {
  val radiusInMeters: Double = 5000d

  val INITIAL_RIDEHAIL_LOCATION_HOME = "HOME"
  val INITIAL_RIDEHAIL_LOCATION_UNIFORM_RANDOM = "UNIFORM_RANDOM"

  def nextRideHailingInquiryId: Id[RideHailingInquiry] = {
    Id.create(UUIDGen.createTime(UUIDGen.newTime()).toString, classOf[RideHailingInquiry])
  }

  case class NotifyIterationEnds()

  case class RideHailingInquiry(inquiryId: Id[RideHailingInquiry],
                                customerId: Id[PersonAgent],
                                pickUpLocation: Location,
                                departAt: BeamTime,
                                destination: Location)

  case class TravelProposal(rideHailingAgentLocation: RideHailingAgentLocation,
                            timesToCustomer: Long,
                            estimatedPrice: BigDecimal,
                            estimatedTravelTime: Option[Duration],
                            responseRideHailing2Pickup: RoutingResponse,
                            responseRideHailing2Dest: RoutingResponse)

  case class RideHailingInquiryResponse(inquiryId: Id[RideHailingInquiry],
                                        proposals: Seq[TravelProposal],
                                        error: Option[ReservationError] = None)

  case class ReserveRide(inquiryId: Id[RideHailingInquiry],
                         customerIds: VehiclePersonId,
                         pickUpLocation: Location,
                         departAt: BeamTime,
                         destination: Location)

  private case class RoutingResponses(customerAgent: Option[ActorRef],
                                      inquiryId: Id[RideHailingInquiry],
                                      personId: Id[PersonAgent],
                                      customerPickUp: Location,
                                      departAt: BeamTime,
                                      rideHailingLocation: RideHailingAgentLocation,
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
