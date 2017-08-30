package beam.agentsim.agents

import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, Props}
import beam.agentsim.agents.BeamAgent.BeamAgentData
import beam.agentsim.agents.RideHailingAgent.PickupCustomer
import beam.agentsim.agents.RideHailingManager._
import beam.agentsim.agents.modalBehaviors.DrivesVehicle.StartLegTrigger
import beam.agentsim.agents.util.{AggregatorFactory, SingleActorAggregationResult}
import beam.agentsim.agents.vehicles.BeamVehicle.StreetVehicle
import beam.agentsim.agents.vehicles.{PassengerSchedule, VehicleManager, VehiclePersonId}
import beam.agentsim.agents.TriggerUtils._
import beam.agentsim.events.SpaceTime
import beam.agentsim.events.resources.ReservationErrorCode.ReservationErrorCode
import beam.agentsim.events.resources.vehicle._
import beam.agentsim.events.resources.{ReservationError, ReservationErrorCode}
import beam.router.BeamRouter.{Location, RoutingRequest, RoutingRequestTripInfo, RoutingResponse}
import beam.router.Modes.BeamMode._
import beam.router.RoutingModel.{BeamTime, BeamTrip}
import beam.router.{BeamRouter, RoutingModel}
import beam.sim.config.ConfigModule._
import beam.sim.{BeamServices, HasServices}
import com.eaio.uuid.UUIDGen
import org.geotools.geometry.DirectPosition2D
import org.geotools.referencing.CRS
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.collections.QuadTree
import org.matsim.core.utils.geometry.CoordUtils
import org.matsim.vehicles.{Vehicle, VehicleType}
import org.slf4j.LoggerFactory

import scala.collection.mutable
import scala.concurrent.duration.{Duration, FiniteDuration}

/**
  * BEAM
  */

object RideHailingManager {
  val log = LoggerFactory.getLogger(classOf[RideHailingManager])

  def nextRideHailingInquiryId: Id[RideHailingInquiry] = Id.create(UUIDGen.createTime(UUIDGen.newTime()).toString, classOf[RideHailingInquiry])

  case class RideHailingInquiry(inquiryId: Id[RideHailingInquiry], customerId: Id[PersonAgent], pickUpLocation: Location, departAt: BeamTime, radius: Double, destination: Location)

  case class TravelProposal(rideHailingAgentLocation: RideHailingAgentLocation, timesToCustomer: Long, estimatedPrice: BigDecimal, estimatedTravelTime: Option[Duration], responseRideHailing2Pickup: RoutingResponse, responseRideHailing2Dest: RoutingResponse)

  case class RideHailingInquiryResponse(inquiryId: Id[RideHailingInquiry], proposals: Vector[TravelProposal], error: Option[ReservationError] = None)

  case object RideUnavailableError extends ReservationError {
    override def errorCode: ReservationErrorCode = ReservationErrorCode.ResourceUnAvailable
  }

  case class ReserveRide(inquiryId: Id[RideHailingInquiry], customerIds: VehiclePersonId, pickUpLocation: Location, departAt: BeamTime, destination: Location)

  case class ReserveRideResponse(inquiryId: Id[RideHailingInquiry], data: Either[ReservationError, RideHailConfirmData])

  case class RideHailConfirmData(rideHailAgent: ActorRef, customerId: Id[PersonAgent], travelProposal: TravelProposal)

  case class RegisterRideAvailable(rideHailingAgent: ActorRef, vehicleId: Id[Vehicle], availableSince: SpaceTime)

  case class RegisterRideUnavailable(ref: ActorRef, location: Coord)

  case object RideUnavailableAck

  case object RideAvailableAck

  case class RideHailingAgentLocation(rideHailAgent: ActorRef, vehicleId: Id[Vehicle], currentLocation: SpaceTime)

  def props(name: String, fares: Map[Id[VehicleType], BigDecimal], fleet: Map[Id[Vehicle], Vehicle], services: BeamServices) = {
    Props(classOf[RideHailingManager], RideHailingManagerData(name, fares, fleet), services)
  }
}

//TODO: Build RHM from XML to be able to specify different kinds of TNC/Rideshare types and attributes
case class RideHailingManagerData(name: String, fares: Map[Id[VehicleType], BigDecimal],
                                  fleet: Map[Id[Vehicle], Vehicle]) extends BeamAgentData

class RideHailingManager(info: RideHailingManagerData, val beamServices: BeamServices) extends VehicleManager
  with HasServices with AggregatorFactory {

  import scala.collection.JavaConverters._

  val DefaultCostPerMile = BigDecimal(beamServices.beamConfig.beam.rideHailing.defaultCostPerMile)
  val DefaultCostPerMinute = BigDecimal(beamServices.beamConfig.beam.rideHailing.defaultCostPerMinute)


  // improve search to take into account time rideHailingAgentSpatialIndex is available
  private val rideHailingAgentSpatialIndex = {
    val bbBuffer = beamServices.beamConfig.bbBuffer
    new QuadTree[RideHailingAgentLocation](
      beamServices.bbox.minX - bbBuffer,
      beamServices.bbox.minY - bbBuffer,
      beamServices.bbox.maxX + bbBuffer,
      beamServices.bbox.maxY + bbBuffer)
  }
  private val availableRideHailVehicles = mutable.Map[Id[Vehicle], RideHailingAgentLocation]()
  private val inServiceRideHailVehicles = mutable.Map[Id[Vehicle], RideHailingAgentLocation]()
  //XXX: let's make sorted-map to be get latest and oldest orders to expire some of the
  private val pendingInquiries = mutable.TreeMap[Id[RideHailingInquiry], (TravelProposal, BeamTrip)]()
  private val pendingModifyPassengerScheduleAcks = mutable.TreeMap[Id[RideHailingInquiry], ReservationResponse]()

  private val transform = CRS.findMathTransform(CRS.decode("EPSG:4326", true), CRS.decode(beamServices.beamConfig.beam.routing.gtfs.crs, true), false)

  override def receive: Receive = {
    case RegisterRideAvailable(rideHailingAgentRef: ActorRef, vehicleId: Id[Vehicle], availableIn: SpaceTime) =>
      // register
      val pos = new DirectPosition2D(availableIn.loc.getX, availableIn.loc.getY)
      val posTransformed = new DirectPosition2D(availableIn.loc.getX, availableIn.loc.getY)
      if (availableIn.loc.getX <= 180.0 & availableIn.loc.getX >= -180.0 & availableIn.loc.getY > -90.0 & availableIn.loc.getY < 90.0) {
        transform.transform(pos, posTransformed)
      }
      val rideHailingAgentLocation = RideHailingAgentLocation(rideHailingAgentRef, vehicleId, availableIn)
      rideHailingAgentSpatialIndex.put(posTransformed.x, posTransformed.y, rideHailingAgentLocation)
      availableRideHailVehicles.put(vehicleId, rideHailingAgentLocation)
      inServiceRideHailVehicles.remove(vehicleId)
      sender ! RideAvailableAck

    case inquiry@RideHailingInquiry(inquiryId, personId, customerPickUp, departAt, radius, destination) =>
      val nearbyRideHailingAgents = rideHailingAgentSpatialIndex.getDisk(inquiry.pickUpLocation.getX, inquiry.pickUpLocation.getY, radius).asScala.toVector
      val distances2RideHailingAgents = nearbyRideHailingAgents.map(rideHailingAgentLocation => {
        val distance = CoordUtils.calcProjectedEuclideanDistance(inquiry.pickUpLocation, rideHailingAgentLocation.currentLocation.loc)
        (rideHailingAgentLocation, distance)
      })
      //TODO: Possibly get multiple taxis in this block
      val chosenRideLocation = distances2RideHailingAgents.sortBy(_._2).headOption
      val customerAgent = sender()
      chosenRideLocation match {
        case Some((rideHailingLocation, shortDistanceToRideHailingAgent)) =>
          //          val params = RoutingRequestParams(departAt, Vector(RIDE_HAILING), vehiclePersonId)

          // This hbv represents a customer agent leg
          val customerAgentBody = StreetVehicle(Id.createVehicleId(s"body-$personId"), SpaceTime((customerPickUp, departAt.atTime)), WALK, asDriver = true)

          val customerTripRequestId = BeamRouter.nextId
          val rideHailing2CustomerRequestId = BeamRouter.nextId
          val rideHailingVehicleAtOrigin = StreetVehicle(rideHailingLocation.vehicleId, SpaceTime((rideHailingLocation.currentLocation.loc, departAt.atTime)), CAR, asDriver = false)
          val rideHailingVehicleAtPickup = StreetVehicle(rideHailingLocation.vehicleId, SpaceTime((customerPickUp, departAt.atTime)), CAR, asDriver = false)
          val routeRequests = Map(
            beamServices.beamRouter.path -> List(
              RoutingRequest(rideHailing2CustomerRequestId, RoutingRequestTripInfo(rideHailingLocation.currentLocation.loc, customerPickUp, departAt, Vector(), Vector(rideHailingVehicleAtOrigin), personId)),
              //XXXX: customer trip request might be redundant... possibly pass in info
              RoutingRequest(customerTripRequestId, RoutingRequestTripInfo(customerPickUp, destination, departAt, Vector(), Vector(customerAgentBody,rideHailingVehicleAtPickup), personId)))
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
            if (timeToCustomer < Long.MaxValue && customerPlans2Costs.nonEmpty) {
              val (customerTripPlan, cost) = customerPlans2Costs.minBy(_._2)

              //TODO: include customerTrip plan in response to reuse( as option BeamTrip can include createdTime to check if the trip plan is still valid
              //TODO: we response with collection of TravelCost to be able to consolidate responses from different ride hailing companies


              val modRHA2Cust = rideHailingAgent2CustomerResponse.itineraries.filter(x => x.tripClassifier.equals(RIDEHAIL)).map(l => l.copy(legs = l.legs.map(c => c.copy(asDriver = true))))
              val modRHA2Dest = rideHailing2DestinationResponse.itineraries.filter(x => x.tripClassifier.equals(RIDEHAIL)).map(l => l.copy(legs = l.legs.map(c => c.copy(asDriver = (c.beamLeg.mode == WALK), unbecomeDriverOnCompletion = c.beamLeg==l.legs(2).beamLeg, beamLeg = c.beamLeg.copy(startTime = c.beamLeg.startTime + timeToCustomer)))))

              val rideHailingAgent2CustomerResponseMod = RoutingResponse(rideHailingAgent2CustomerResponse.id, modRHA2Cust)
              val rideHailing2DestinationResponseMod = RoutingResponse(rideHailing2DestinationResponse.id, modRHA2Dest)

              val travelProposal = TravelProposal(rideHailingLocation, timeToCustomer, cost, Option(FiniteDuration(customerTripPlan.totalTravelTime, TimeUnit.SECONDS)), rideHailingAgent2CustomerResponseMod, rideHailing2DestinationResponseMod)
              pendingInquiries.put(inquiryId, (travelProposal, modRHA2Dest.head.toBeamTrip()))
              log.info(s"Found ride to hail for  person=$personId and inquiryId=$inquiryId within $shortDistanceToRideHailingAgent meters, timeToCustomer=$timeToCustomer seconds and cost=$$$cost")
              RideHailingInquiryResponse(inquiryId, Vector(travelProposal))
            } else {
              log.warn(s"Router could not find route to customer person=$personId for inquiryId=$inquiryId")
              RideHailingInquiryResponse(inquiryId, Vector(), error = Option(CouldNotFindRouteToCustomer))
            }
          }
        case None =>
          // no rides to hail
          customerAgent ! RideHailingInquiryResponse(inquiryId, Vector(), error = Option(VehicleUnavailable))
      }
    case ReserveRide(inquiryId, vehiclePersonIds, customerPickUp, departAt, destination) =>
      //TODO: probably it make sense to add some expiration time (TTL) to pending inquiries
      val travelPlanOpt = pendingInquiries.remove(inquiryId)
      val customerAgent = sender()
      /**
        * 1. customerAgent ! ReserveRideConfirmation(rideHailingAgentSpatialIndex, customerId, travelProposal)
        * 2. rideHailingAgentSpatialIndex ! PickupCustomer
        */
      Option(rideHailingAgentSpatialIndex.getClosest(customerPickUp.getX, customerPickUp.getY)) match {
        case Some(closestRideHailingAgent) if travelPlanOpt.isDefined && closestRideHailingAgent == travelPlanOpt.get._1.rideHailingAgentLocation =>
          val travelProposal = travelPlanOpt.get._1
          val tripPlan = travelPlanOpt.map(_._2)
          handleReservation(inquiryId, vehiclePersonIds, customerPickUp, destination, customerAgent, closestRideHailingAgent, travelProposal, tripPlan)
//        case Some(closestRideHailingAgent) =>
//          handleReservation(inquiryId, closestRideHailingAgent, vehiclePersonIds, customerPickUp, departAt, destination, customerAgent)
        case None =>
          customerAgent ! ReservationResponse(Id.create(inquiryId.toString,classOf[ReservationRequest]), Left(VehicleUnavailable))
      }
    case ModifyPassengerScheduleAck(inquiryIDOption) =>
      completeReservation(Id.create(inquiryIDOption.get.toString,classOf[RideHailingInquiry]))
    case msg =>
      log.info(s"unknown message received by RideHailingManager $msg")
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
    passengerSchedule.addLegs(travelProposal.responseRideHailing2Pickup.itineraries.head.toBeamTrip.legs)  // Adds empty trip to customer
    passengerSchedule.addPassenger(vehiclePersonId,trip2DestPlan.get.legs.filter(_.mode==CAR))             // Adds customer's actual trip to destination
    inServiceRideHailVehicles.put(closestRideHailingAgentLocation.vehicleId, closestRideHailingAgentLocation)
    rideHailingAgentSpatialIndex.remove(closestRideHailingAgentLocation.currentLocation.loc.getX, closestRideHailingAgentLocation.currentLocation.loc.getY, closestRideHailingAgentLocation)

    // Create confirmation info but stash until we receive ModifyPassengerScheduleAck
    val triggerToSchedule = schedule[StartLegTrigger](passengerSchedule.schedule.firstKey.startTime,closestRideHailingAgentLocation.rideHailAgent,passengerSchedule.schedule.firstKey)
    pendingModifyPassengerScheduleAcks.put(inquiryId, ReservationResponse(Id.create(inquiryId.toString,classOf[ReservationRequest]), Right(ReserveConfirmInfo(trip2DestPlan.head.legs.head, trip2DestPlan.last.legs.last,vehiclePersonId,triggerToSchedule))))

    closestRideHailingAgentLocation.rideHailAgent ! ModifyPassengerSchedule(passengerSchedule, Some(inquiryId))
  }

  private def completeReservation(inquiryId: Id[RideHailingInquiry]): Unit ={
    val response = pendingModifyPassengerScheduleAcks.remove(inquiryId).get
    val customerRef = beamServices.personRefs(response.response.right.get.passengerVehiclePersonId.personId)
    customerRef ! response
  }

//  triggerCustomerPickUp(customerPickUp, destination, closestRideHailingAgentLocation, trip2DestPlan, travelProposal.responseRideHailing2Pickup.itineraries.head.toBeamTrip(), confirmation, vehiclePersonId)
  private def triggerCustomerPickUp(customerPickUp: Location, destination: Location, closestRideHailingAgentLocation: RideHailingAgentLocation, trip2DestPlan: Option[BeamTrip], trip2CustPlan:BeamTrip, confirmation: ReservationResponse, personId:Id[Person]) = {
  }

  private def findVehicle(resourceId: Id[Vehicle]): Option[Vehicle] = {
    info.fleet.get(resourceId)
  }

  override def findResource(resourceId: Id[Vehicle]): Option[ActorRef] = ???

}
