package beam.agentsim.agents

import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, Props}
import beam.agentsim.Resource.ResourceIsAvailableNotification
import beam.agentsim.ResourceManager.VehicleManager
import beam.agentsim.agents.BeamAgent.BeamAgentData
import beam.agentsim.agents.RideHailingManager._
import beam.agentsim.agents.TriggerUtils._
import beam.agentsim.agents.modalBehaviors.DrivesVehicle.StartLegTrigger
import beam.agentsim.agents.util.{AggregatorFactory, SingleActorAggregationResult}
import beam.agentsim.agents.vehicles.BeamVehicle.StreetVehicle
import beam.agentsim.agents.vehicles.household.HouseholdActor.ReleaseVehicleReservation
import beam.agentsim.agents.vehicles.{PassengerSchedule, VehiclePersonId}
import beam.agentsim.events.SpaceTime
import beam.agentsim.events.resources.ReservationErrorCode.ReservationErrorCode
import beam.agentsim.events.resources.vehicle._
import beam.agentsim.events.resources.{ReservationError, ReservationErrorCode}
import beam.router.BeamRouter.{Location, RoutingRequest, RoutingRequestTripInfo, RoutingResponse}
import beam.router.Modes.BeamMode._
import beam.router.RoutingModel.{BeamTime, BeamTrip}
import beam.router.{BeamRouter, RoutingModel}
import beam.sim.{BeamServices, HasServices}
import com.eaio.uuid.UUIDGen
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.collections.QuadTree
import org.matsim.core.utils.geometry.CoordUtils
import org.matsim.vehicles.{Vehicle, VehicleType}
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.duration.{Duration, FiniteDuration}

/**
  * BEAM
  */

object RideHailingManager {
  val log: Logger = LoggerFactory.getLogger(classOf[RideHailingManager])

  def nextRideHailingInquiryId: Id[RideHailingInquiry] = Id.create(UUIDGen.createTime(UUIDGen.newTime()).toString, classOf[RideHailingInquiry])

  case class RideHailingInquiry(inquiryId: Id[RideHailingInquiry], customerId: Id[PersonAgent], pickUpLocation: Location, departAt: BeamTime, destination: Location)

  case class TravelProposal(rideHailingAgentLocation: RideHailingAgentLocation, timesToCustomer: Long, estimatedPrice: BigDecimal, estimatedTravelTime: Option[Duration], responseRideHailing2Pickup: RoutingResponse, responseRideHailing2Dest: RoutingResponse)

  case class RideHailingInquiryResponse(inquiryId: Id[RideHailingInquiry], proposals: Vector[TravelProposal], error: Option[ReservationError] = None)

  case class ReserveRide(inquiryId: Id[RideHailingInquiry], customerIds: VehiclePersonId, pickUpLocation: Location, departAt: BeamTime, destination: Location)

  case class ReserveRideResponse(inquiryId: Id[RideHailingInquiry], data: Either[ReservationError, RideHailConfirmData])

  case class RideHailConfirmData(rideHailAgent: ActorRef, customerId: Id[PersonAgent], travelProposal: TravelProposal)

  case class RegisterRideAvailable(rideHailingAgent: ActorRef, vehicleId: Id[Vehicle], availableSince: SpaceTime)

  case class RegisterRideUnavailable(ref: ActorRef, location: Coord)

  case object RideUnavailableAck

  case object RideAvailableAck

  case class RideHailingAgentLocation(rideHailAgent: ActorRef, vehicleId: Id[Vehicle], currentLocation: SpaceTime)

  def props(name: String, fares: Map[Id[VehicleType], BigDecimal], fleet: Map[Id[Vehicle], Vehicle], services: BeamServices, managedVehicles: Map[Id[Vehicle], ActorRef]) = {
    Props(classOf[RideHailingManager], RideHailingManagerData(name, fares, fleet), services, managedVehicles: Map[Id[Vehicle], ActorRef])
  }
}

//TODO: Build RHM from XML to be able to specify different kinds of TNC/Rideshare types and attributes
case class RideHailingManagerData(name: String, fares: Map[Id[VehicleType], BigDecimal],
                                  fleet: Map[Id[Vehicle], Vehicle]) extends BeamAgentData

class RideHailingManager(info: RideHailingManagerData,
                         val beamServices: BeamServices,
                         val managedVehicles: Map[Id[Vehicle], ActorRef]) extends VehicleManager with HasServices with AggregatorFactory {

  import scala.collection.JavaConverters._

  override val resources: Map[Id[Vehicle], ActorRef] = managedVehicles

  val DefaultCostPerMile = BigDecimal(beamServices.beamConfig.beam.agentsim.agents.rideHailing.defaultCostPerMile)
  val DefaultCostPerMinute = BigDecimal(beamServices.beamConfig.beam.agentsim.agents.rideHailing.defaultCostPerMinute)
  val radius: Double = 5000

  // improve search to take into account time rideHailingAgentSpatialIndex is available
  private val rideHailingAgentSpatialIndex = {
    new QuadTree[RideHailingAgentLocation](
      beamServices.geo.utmbbox.minX,
      beamServices.geo.utmbbox.minY,
      beamServices.geo.utmbbox.maxX,
      beamServices.geo.utmbbox.maxY)
  }
  private val availableRideHailVehicles = collection.concurrent.TrieMap[Id[Vehicle], RideHailingAgentLocation]()
  private var lockedVehicles = Set[Id[Vehicle]]()
  private val inServiceRideHailVehicles = collection.concurrent.TrieMap[Id[Vehicle], RideHailingAgentLocation]()
  //TODO: let's make sorted-map to be get latest and oldest orders to expire some of the
  private val pendingInquiries = collection.concurrent.TrieMap[Id[RideHailingInquiry], (TravelProposal, BeamTrip)]()
  private val pendingModifyPassengerScheduleAcks = collection.concurrent.TrieMap[Id[RideHailingInquiry], ReservationResponse]()

  override def receive: Receive = {

    case ResourceIsAvailableNotification(vehicleAgentRef: ActorRef, vehicleId: Id[Vehicle], availableIn: SpaceTime) =>

      val rideHailingAgentLocation = RideHailingAgentLocation(vehicleAgentRef, vehicleId, availableIn)
      rideHailingAgentSpatialIndex.put(availableIn.loc.getX, availableIn.loc.getY, rideHailingAgentLocation)
      availableRideHailVehicles.put(vehicleId, rideHailingAgentLocation)
      inServiceRideHailVehicles.remove(vehicleId)

      sender ! RideAvailableAck

    case RideHailingInquiry(inquiryId, personId, customerPickUp, departAt, destination) =>

      val customerAgent = sender()
      getClosestRideHailingAgent(customerPickUp, radius) match {
        case Some((rideHailingLocation, shortDistanceToRideHailingAgent)) =>
          //          val params = RoutingRequestParams(departAt, Vector(RIDE_HAILING), vehiclePersonId)
          lockedVehicles += rideHailingLocation.vehicleId
          // This hbv represents a customer agent leg
          val customerAgentBody = StreetVehicle(Id.createVehicleId(s"body-$personId"), SpaceTime((customerPickUp, departAt.atTime)), WALK, asDriver = true)

          val customerTripRequestId = BeamRouter.nextId
          val rideHailing2CustomerRequestId = BeamRouter.nextId
          val rideHailingVehicleAtOrigin = StreetVehicle(rideHailingLocation.vehicleId, SpaceTime((rideHailingLocation.currentLocation.loc, departAt.atTime)), CAR, asDriver = false)
          val rideHailingVehicleAtPickup = StreetVehicle(rideHailingLocation.vehicleId, SpaceTime((customerPickUp, departAt.atTime)), CAR, asDriver = false)
          val routeRequests = Map(
            beamServices.beamRouter.path -> List(
              RoutingRequest(rideHailing2CustomerRequestId, RoutingRequestTripInfo(rideHailingLocation.currentLocation.loc, customerPickUp, departAt, Vector(),
                Vector(rideHailingVehicleAtOrigin), personId)),
              //XXXX: customer trip request might be redundant... possibly pass in info
              RoutingRequest(customerTripRequestId, RoutingRequestTripInfo(customerPickUp, destination, departAt, Vector(), Vector(customerAgentBody, rideHailingVehicleAtPickup), personId)))
          )

          aggregateResponsesTo(customerAgent, routeRequests, Option(self)) { case result: SingleActorAggregationResult =>
            val responses = result.mapListTo[RoutingResponse].map(res => (res.id, res)).toMap
            val rideHailingAgent2CustomerResponse = responses(rideHailing2CustomerRequestId)
            val rideHailing2DestinationResponse = responses(customerTripRequestId)

            val timesToCustomer: Vector[Long] = rideHailingAgent2CustomerResponse.itineraries.map(t => t.totalTravelTime)
            // TODO: Find better way of doing this error checking than sentry value
            val timeToCustomer = if (timesToCustomer.nonEmpty) {
              timesToCustomer.min
            } else Long.MaxValue
            // TODO: Do unit conversion elsewhere... use squants or homegrown unit conversions, but enforce
            val rideHailingFare = DefaultCostPerMinute / 60.0


            val customerPlans2Costs: Map[RoutingModel.EmbodiedBeamTrip, BigDecimal] = rideHailing2DestinationResponse.itineraries.map(t => (t, rideHailingFare * t.totalTravelTime)).toMap
            val itins2Cust = rideHailingAgent2CustomerResponse.itineraries.filter(x => x.tripClassifier.equals(RIDEHAIL))
            val itins2Dest = rideHailing2DestinationResponse.itineraries.filter(x => x.tripClassifier.equals(RIDEHAIL))
            if (timeToCustomer < Long.MaxValue && customerPlans2Costs.nonEmpty && itins2Cust.nonEmpty && itins2Dest.nonEmpty) {
              val (customerTripPlan, cost) = customerPlans2Costs.minBy(_._2)

              //TODO: include customerTrip plan in response to reuse( as option BeamTrip can include createdTime to check if the trip plan is still valid
              //TODO: we response with collection of TravelCost to be able to consolidate responses from different ride hailing companies

              val modRHA2Cust = itins2Cust.map(l => l.copy(legs = l.legs.map(c => c.copy(asDriver = true))))
              val modRHA2Dest = itins2Dest.map(l => l.copy(legs = l.legs.map(c => c.copy(asDriver = c.beamLeg.mode == WALK,
                unbecomeDriverOnCompletion = c.beamLeg == l.legs(2).beamLeg,
                beamLeg = c.beamLeg.copy(startTime = c.beamLeg.startTime + timeToCustomer),
                cost = if(c.beamLeg == l.legs(1).beamLeg){ cost }else{ 0.0 }
              ))))

              val rideHailingAgent2CustomerResponseMod = RoutingResponse(rideHailingAgent2CustomerResponse.id, modRHA2Cust)
              val rideHailing2DestinationResponseMod = RoutingResponse(rideHailing2DestinationResponse.id, modRHA2Dest)

              val travelProposal = TravelProposal(rideHailingLocation, timeToCustomer, cost, Option(FiniteDuration(customerTripPlan.totalTravelTime, TimeUnit.SECONDS)), rideHailingAgent2CustomerResponseMod, rideHailing2DestinationResponseMod)
              pendingInquiries.put(inquiryId, (travelProposal, modRHA2Dest.head.toBeamTrip()))
              log.debug(s"Found ride to hail for  person=$personId and inquiryId=$inquiryId within $shortDistanceToRideHailingAgent meters, timeToCustomer=$timeToCustomer seconds and cost=$$$cost")
              RideHailingInquiryResponse(inquiryId, Vector(travelProposal))
            } else {
              log.debug(s"Router could not find route to customer person=$personId for inquiryId=$inquiryId")
              lockedVehicles -= rideHailingLocation.vehicleId
              RideHailingInquiryResponse(inquiryId, Vector(), error = Option(CouldNotFindRouteToCustomer))
            }
          }
        case None =>
          // no rides to hail
//          log.debug(s"Router could not find vehicle for customer person=$personId for inquiryId=$inquiryId")
          customerAgent ! RideHailingInquiryResponse(inquiryId, Vector(), error = Option(CouldNotFindRouteToCustomer))
      }

    case ReserveRide(inquiryId, vehiclePersonIds, customerPickUp, departAt, destination) =>
      if (pendingInquiries.contains(inquiryId)) {
        //TODO: probably it make sense to add some expiration time (TTL) to pending inquiries
        val travelPlanOpt = pendingInquiries.remove(inquiryId)
        val customerAgent = sender()
        /**
          * 1. customerAgent ! ReserveRideConfirmation(rideHailingAgentSpatialIndex, customerId, travelProposal)
          * 2. rideHailingAgentSpatialIndex ! PickupCustomer
          */
        val nearbyRideHailingAgents = rideHailingAgentSpatialIndex.getDisk(customerPickUp.getX, customerPickUp.getY, radius).asScala.toVector
        val closestRHA: Option[RideHailingAgentLocation] = nearbyRideHailingAgents.filter(x =>
          lockedVehicles(x.vehicleId)).find(_.vehicleId.equals(travelPlanOpt.get._1.responseRideHailing2Pickup.itineraries.head.vehiclesInTrip.head))

        closestRHA match {
          case Some((closestRideHailingAgent)) =>
            val travelProposal = travelPlanOpt.get._1
            val tripPlan = travelPlanOpt.map(_._2)
            handleReservation(inquiryId, vehiclePersonIds, customerPickUp, destination, customerAgent, closestRideHailingAgent, travelProposal, tripPlan)
            // We have an agent nearby, but it's not the one we originally wanted
          case _ =>
            customerAgent ! ReservationResponse(Id.create(inquiryId.toString, classOf[ReservationRequest]), Left(UnknownRideHailReservationError))
        }
      } else {
        sender() ! ReservationResponse(Id.create(inquiryId.toString, classOf[ReservationRequest]), Left(UnknownInquiryId))
      }
    case ModifyPassengerScheduleAck(inquiryIDOption) =>
      completeReservation(Id.create(inquiryIDOption.get.toString, classOf[RideHailingInquiry]))

    case ReleaseVehicleReservation(personId,vehId)=>
      lockedVehicles -= vehId

    case msg =>
      log.debug(s"unknown message received by RideHailingManager $msg")


  }



  //  private def handleReservation(inquiryId: Id[RideHailingInquiry], closestRideHailingAgentLocation: RideHailingAgentLocation, vehiclePersonId: Id[PersonAgent], customerPickUp: Location, departAt: BeamTime, destination: Location, customerAgent: ActorRef) = {
  //    //    val params = RoutingRequestParams(departAt, Vector(TAXI), vehiclePersonId)
  //    val customerTripRequestId = BeamRouter.nextId
  //    val routeRequests = Map(
  //      beamServices.beamRouter.path -> List(
  //        //TODO update based on new Request spec
  //        RoutingRequest(customerTripRequestId, RoutingRequestTripInfo(customerPickUp, destination, departAt, Vector(RideHailing), Vector(), vehiclePersonId))
  //      ))
  //    aggregateResponsesTo(customerAgent, routeRequests) { case result: SingleActorAggregationResult =>
  //      val customerTripPlan = result.mapListTo[RoutingResponse].headOption
  //      val rideHailingFare = DefaultCostPerMinute / 60.0
  //      val tripAndCostOpt = customerTripPlan.map(_.itineraries.map(t => (t, rideHailingFare * t.totalTravelTime)).minBy(_._2))
  //      val responseToCustomer = tripAndCostOpt.map { case (tripRoute, cost) =>
  //        //XXX: we didn't find rideHailing inquiry in pendingInquiries let's set max pickup time to avoid another routing request
  //        val timeToCustomer = beamServices.beamConfig.MaxPickupTimeInSeconds
  //        val travelProposal = TravelProposal(closestRideHailingAgentLocation, timeToCustomer, cost, Option(FiniteDuration(tripRoute.totalTravelTime, TimeUnit.SECONDS)), customerTripPlan.get, customerTripPlan.get)
  //        val confirmation = ReservationResponse(Id.create(inquiryId.toString,classOf[ReservationRequest]), Right(ReserveConfirmInfo(closestRideHailingAgentLocation.vehicleId, vehiclePersonId, travelProposal)))
  //        triggerCustomerPickUp(customerPickUp, destination, closestRideHailingAgentLocation, Option(tripRoute.toBeamTrip()), confirmation)
  //        confirmation
  //      }.getOrElse {
  //        ReserveRideResponse(inquiryId, Left(VehicleUnavailable))
  //      }
  //      responseToCustomer
  //    }
  //  }

  private def handleReservation(inquiryId: Id[RideHailingInquiry], vehiclePersonId: VehiclePersonId, customerPickUp: Location, destination: Location,
                                customerAgent: ActorRef, closestRideHailingAgentLocation: RideHailingAgentLocation, travelProposal: TravelProposal, trip2DestPlan: Option[BeamTrip]) = {

    // Modify RH agent passenger schedule and create BeamAgentScheduler message that will dispatch RH agent to do the pickup
    val passengerSchedule = PassengerSchedule()
    passengerSchedule.addLegs(travelProposal.responseRideHailing2Pickup.itineraries.head.toBeamTrip.legs) // Adds empty trip to customer
    passengerSchedule.addPassenger(vehiclePersonId, trip2DestPlan.get.legs.filter(_.mode == CAR)) // Adds customer's actual trip to destination
    inServiceRideHailVehicles.put(closestRideHailingAgentLocation.vehicleId, closestRideHailingAgentLocation)
    lockedVehicles -= closestRideHailingAgentLocation.vehicleId
    rideHailingAgentSpatialIndex.remove(closestRideHailingAgentLocation.currentLocation.loc.getX, closestRideHailingAgentLocation.currentLocation.loc.getY, closestRideHailingAgentLocation)

    // Create confirmation info but stash until we receive ModifyPassengerScheduleAck
    val triggerToSchedule = schedule[StartLegTrigger](passengerSchedule.schedule.firstKey.startTime, closestRideHailingAgentLocation.rideHailAgent, passengerSchedule.schedule.firstKey)
    pendingModifyPassengerScheduleAcks.put(inquiryId, ReservationResponse(Id.create(inquiryId.toString, classOf[ReservationRequest]), Right(ReserveConfirmInfo(trip2DestPlan.head.legs.head, trip2DestPlan.last.legs.last, vehiclePersonId, triggerToSchedule))))
    closestRideHailingAgentLocation.rideHailAgent ! ModifyPassengerSchedule(passengerSchedule, Some(inquiryId))
  }

  private def completeReservation(inquiryId: Id[RideHailingInquiry]): Unit = {
    pendingModifyPassengerScheduleAcks.remove(inquiryId) match {
      case Some(response) =>
        log.debug(s"Completed reservation for $inquiryId")
        val customerRef = beamServices.personRefs(response.response.right.get.passengerVehiclePersonId.personId)
        customerRef ! response
      case None =>
        log.error(s"Vehicle was reserved by another agent for inquiry id $inquiryId")
        sender() ! ReservationResponse(Id.create(inquiryId.toString, classOf[ReservationRequest]), Left(RideHailVehicleTaken))
    }

  }

  //  triggerCustomerPickUp(customerPickUp, destination, closestRideHailingAgentLocation, trip2DestPlan, travelProposal.responseRideHailing2Pickup.itineraries.head.toBeamTrip(), confirmation, vehiclePersonId)
  private def triggerCustomerPickUp(customerPickUp: Location, destination: Location, closestRideHailingAgentLocation: RideHailingAgentLocation, trip2DestPlan: Option[BeamTrip], trip2CustPlan: BeamTrip, confirmation: ReservationResponse, personId: Id[Person]) = {
  }

  private def findVehicle(resourceId: Id[Vehicle]): Option[Vehicle] = {
    info.fleet.get(resourceId)
  }


  private def getClosestRideHailingAgent(pickupLocation: Coord, radius: Double): Option[(RideHailingAgentLocation, Double)] = {
    val nearbyRideHailingAgents = rideHailingAgentSpatialIndex.getDisk(pickupLocation.getX, pickupLocation.getY, radius).asScala.toVector
    val distances2RideHailingAgents = nearbyRideHailingAgents.map(rideHailingAgentLocation => {
      val distance = CoordUtils.calcProjectedEuclideanDistance(pickupLocation, rideHailingAgentLocation.currentLocation.loc)
      (rideHailingAgentLocation, distance)
    })
    //TODO: Possibly get multiple taxis in this block
    distances2RideHailingAgents.sortBy(_._2).filterNot(x => lockedVehicles(x._1.vehicleId)).headOption
  }

  override def findResource(resourceId: Id[Vehicle]): Option[ActorRef] = ???


}


