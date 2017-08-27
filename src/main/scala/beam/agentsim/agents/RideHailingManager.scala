package beam.agentsim.agents

import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, Props}
import beam.agentsim.agents.BeamAgent.BeamAgentData
import beam.agentsim.agents.RideHailingManager._
import beam.agentsim.agents.RideHailingAgent.PickupCustomer
import beam.agentsim.agents.util.{AggregatorFactory, SingleActorAggregationResult}
import beam.agentsim.agents.vehicles.BeamVehicle.StreetVehicle
import beam.agentsim.agents.vehicles.VehicleManager
import beam.agentsim.events.SpaceTime
import beam.agentsim.events.resources.{ReservationError, ReservationErrorCode}
import beam.agentsim.events.resources.ReservationErrorCode.ReservationErrorCode
import beam.agentsim.events.resources.vehicle.{CouldNotFindRouteToCustomer, VehicleUnavailable}
import beam.router.{BeamRouter, Modes}
import beam.router.BeamRouter.{Location, RoutingRequest, RoutingRequestTripInfo, RoutingResponse}
import beam.router.Modes.BeamMode._
import beam.router.RoutingModel.{BeamTime, BeamTrip}
import beam.sim.{BeamServices, HasServices}
import com.eaio.uuid.UUIDGen
import org.geotools.geometry.DirectPosition2D
import org.geotools.referencing.CRS
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.collections.QuadTree
import org.matsim.core.utils.geometry.CoordUtils
import org.matsim.vehicles.{Vehicle, VehicleType}
import org.slf4j.LoggerFactory

import scala.collection.{immutable, mutable}
import scala.concurrent.duration.{Duration, FiniteDuration}
import beam.sim.config.ConfigModule._
/**
  * BEAM
  */

object RideHailingManager {
  val log = LoggerFactory.getLogger(classOf[RideHailingManager])

  def nextRideHailingInquiryId = Id.create(UUIDGen.createTime(UUIDGen.newTime()).toString, classOf[RideHailingInquiry])

  case class RideHailingInquiry(inquiryId: Id[RideHailingInquiry], customerId: Id[PersonAgent], pickUpLocation: Location, departAt: BeamTime, radius: Double, destination: Location)

  case class TravelProposal(rideHailingAgentLocation: RideHailingAgentLocation, timesToCustomer: Long, estimatedPrice: BigDecimal, estimatedTravelTime: Option[Duration])

  case class RideHailingInquiryResponse(inquiryId: Id[RideHailingInquiry], proposals: Vector[TravelProposal], error: Option[ReservationError] = None)
  case object RideUnavailableError extends ReservationError {
    override def errorCode: ReservationErrorCode = ReservationErrorCode.ResourceUnAvailable
  }

  case class ReserveRide(inquiryId: Id[RideHailingInquiry], customerId: Id[PersonAgent], pickUpLocation: Location, departAt: BeamTime, destination: Location)
  case class ReserveRideResponse(inquiryId: Id[RideHailingInquiry], data: Either[ReservationError, RideHailConfirmData])
  case class RideHailConfirmData(rideHailAgent: ActorRef, customerId: Id[PersonAgent], travelProposal: TravelProposal)

  case class RegisterRideAvailable(rideHailingAgent: ActorRef, vehicleId: Id[Vehicle], availableSince: SpaceTime)
  case class RegisterRideUnavailable(ref: ActorRef, location: Coord )
  case object RideUnavailableAck
  case object RideAvailableAck

  case class RideHailingAgentLocation(rideHailAgent: ActorRef, vehicleId: Id[Vehicle], currentLocation: SpaceTime)

  def props(name: String,fares: Map[Id[VehicleType], BigDecimal], fleet: Map[Id[Vehicle], Vehicle],  services: BeamServices) = {
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
  // improve search to take into account time rideHailingAgent is available
  private val rideHailingAgent = {
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
  private val pendingInquiries = mutable.TreeMap[Id[RideHailingInquiry],(TravelProposal, BeamTrip)]()

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
      rideHailingAgent.put(posTransformed.x,posTransformed.y, rideHailingAgentLocation)
      availableRideHailVehicles.put(vehicleId, rideHailingAgentLocation)
      inServiceRideHailVehicles.remove(vehicleId)
      sender ! RideAvailableAck

    case inquiry@RideHailingInquiry(inquiryId, personId, customerPickUp, departAt, radius, destination) =>
      val nearbyRideHailingAgents = rideHailingAgent.getDisk(inquiry.pickUpLocation.getX, inquiry.pickUpLocation.getY, radius).asScala.toVector
      val distances2RideHailingAgents = nearbyRideHailingAgents.map(rideHailingAgentLocation => {
        val distance = CoordUtils.calcProjectedEuclideanDistance(inquiry.pickUpLocation, rideHailingAgentLocation.currentLocation.loc)
        (rideHailingAgentLocation, distance)
      })
      //TODO: Possibly get multiple taxis in this block
      val chosenRideLocation = distances2RideHailingAgents.sortBy(_._2).headOption
      val customerAgent = sender()
      chosenRideLocation match {
        case Some((rideHailingLocation, shortDistanceToRideHailingAgent)) =>
      //          val params = RoutingRequestParams(departAt, Vector(RIDE_HAILING), personId)

          // This hbv represents a dummy walk leg for the taxi agent
          val rideHailingAgentBody = StreetVehicle(Id.createVehicleId(s"body-$inquiryId"),SpaceTime((rideHailingLocation.currentLocation.loc,departAt.atTime)),WALK)
          val customerAgentBody = StreetVehicle(Id.createVehicleId(s"body-$personId"), SpaceTime((customerPickUp,departAt.atTime)),WALK)

          val customerTripRequestId = BeamRouter.nextId
          val rideHailing2CustomerRequestId = BeamRouter.nextId
          val rideHailingVehicleAtOrigin = StreetVehicle(rideHailingLocation.vehicleId, SpaceTime((rideHailingLocation.currentLocation.loc,departAt.atTime)), CAR)
          val rideHailingVehicleAtPickup = StreetVehicle(rideHailingLocation.vehicleId, SpaceTime((customerPickUp,departAt.atTime)), CAR)
          val routeRequests = Map(
            beamServices.beamRouter.path -> List(
              RoutingRequest(rideHailing2CustomerRequestId, RoutingRequestTripInfo(rideHailingLocation.currentLocation.loc, customerPickUp, departAt, Vector(), Vector(rideHailingAgentBody,rideHailingVehicleAtOrigin), personId)),
              //XXXX: customer trip request might be redundant... possibly pass in info
              RoutingRequest(customerTripRequestId, RoutingRequestTripInfo(customerPickUp, destination, departAt, Vector(), Vector(customerAgentBody,rideHailingVehicleAtPickup), personId)))
          )
          aggregateResponsesTo(customerAgent, routeRequests,Option(self)){ case result: SingleActorAggregationResult =>
            val responses = result.mapListTo[RoutingResponse].map(res => (res.id, res)).toMap
            val timesToCustomer: Vector[Long] = responses(rideHailing2CustomerRequestId).itineraries.map(t => t.totalTravelTime)
            val timeToCustomer = if(timesToCustomer.nonEmpty){timesToCustomer.min}else Long.MaxValue

            val rideHailingFare = findVehicle(rideHailingLocation.vehicleId).flatMap(vehicle => info.fares.get(vehicle.getType.getId)).getOrElse(DefaultCostPerMile)
            val (customerTripPlan, cost) = responses(customerTripRequestId).itineraries.map(t => (t, t.estimateCost(rideHailingFare).min)).minBy(_._2)
            //TODO: include customerTrip plan in response to reuse( as option BeamTrip can include createdTime to check if the trip plan is still valid
            //TODO: we response with collection of TravelCost to be able to consolidate responses from different ride hailing companies

            val travelProposal = TravelProposal(rideHailingLocation, timeToCustomer, cost, Option(FiniteDuration(customerTripPlan.totalTravelTime, TimeUnit.SECONDS)))
            pendingInquiries.put(inquiryId, (travelProposal, customerTripPlan.toBeamTrip))
            if(timeToCustomer==Long.MaxValue){
              log.warn(s"Router could not find route to customer person=$personId for inquiryId=$inquiryId")
              RideHailingInquiryResponse(inquiryId,Vector(), error = Option(CouldNotFindRouteToCustomer))
            }else {
              log.info(s"Found ride to hail ${rideHailingLocation.currentLocation} for  person=$personId and inquiryId=$inquiryId within $shortDistanceToRideHailingAgent meters, timeToCustomer=$timeToCustomer" )
              RideHailingInquiryResponse(inquiryId, Vector(travelProposal))
            }
          }
        case None =>
          // no rides to hail
          customerAgent !  RideHailingInquiryResponse(inquiryId, Vector(), error = Option(VehicleUnavailable))
      }
    case ReserveRide(inquiryId, personId, customerPickUp, departAt, destination) =>
      //TODO: probably it make sense to add some expiration time (TTL) to pending inquiries
      val travelPlanOpt = pendingInquiries.remove(inquiryId)
      val customerAgent = sender()
      /**
        * 1. customerAgent ! ReserveRideConfirmation(rideHailingAgent, customerId, travelProposal)
        * 2. rideHailingAgent ! PickupCustomer
        */
      Option(rideHailingAgent.getClosest(customerPickUp.getX, customerPickUp.getY)) match {
        case Some(closestRideHailingAgent) if travelPlanOpt.isDefined && closestRideHailingAgent == travelPlanOpt.get._1.rideHailingAgentLocation =>
          val travelProposal = travelPlanOpt.get._1
          val tripPlan = travelPlanOpt.map(_._2)
          handleReservation(inquiryId, personId, customerPickUp, destination, customerAgent, closestRideHailingAgent, travelProposal, tripPlan)
        case Some(closestRideHailingAgent) =>
          handleReservation(inquiryId, closestRideHailingAgent, personId, customerPickUp, departAt, destination, customerAgent)
        case None =>
          customerAgent ! ReserveRideResponse(inquiryId, Left(VehicleUnavailable))
      }
    case msg =>
      log.info(s"unknown message received by RideHailingManager $msg")
  }

  private def handleReservation(inquiryId: Id[RideHailingInquiry], closestRideHailingAgentLocation: RideHailingAgentLocation, personId: Id[PersonAgent], customerPickUp: Location, departAt: BeamTime, destination: Location, customerAgent: ActorRef) = {
//    val params = RoutingRequestParams(departAt, Vector(TAXI), personId)
    val customerTripRequestId = BeamRouter.nextId
    val routeRequests = Map(
      beamServices.beamRouter.path -> List(
        //TODO update based on new Request spec
        RoutingRequest(customerTripRequestId, RoutingRequestTripInfo(customerPickUp, destination, departAt, Vector(RideHailing), Vector(), personId))
      ))
    aggregateResponsesTo(customerAgent, routeRequests) { case result: SingleActorAggregationResult =>
      val customerTripPlan = result.mapListTo[RoutingResponse].headOption
      val rideHailingFare = findVehicle(closestRideHailingAgentLocation.vehicleId).flatMap(vehicle => info.fares.get(vehicle.getType.getId)).getOrElse(DefaultCostPerMile)
      val tripAndCostOpt = customerTripPlan.map(_.itineraries.map(t => (t, t.estimateCost(rideHailingFare).min)).minBy(_._2))
      val responseToCustomer = tripAndCostOpt.map { case (tripRoute, cost) =>
        //XXX: we didn't find rideHailing inquiry in pendingInquiries let's set max pickup time to avoid another routing request
        val timeToCustomer = beamServices.beamConfig.MaxPickupTimeInSeconds
        val travelProposal = TravelProposal(closestRideHailingAgentLocation, timeToCustomer, cost, Option(FiniteDuration(tripRoute.totalTravelTime, TimeUnit.SECONDS)))
        val confirmation = ReserveRideResponse(inquiryId, Right(RideHailConfirmData(closestRideHailingAgentLocation.rideHailAgent, personId, travelProposal)))
        triggerCustomerPickUp(customerPickUp, destination, closestRideHailingAgentLocation, Option(tripRoute.toBeamTrip()), confirmation)
        confirmation
      }.getOrElse {
        ReserveRideResponse(inquiryId, Left(VehicleUnavailable))
      }
      responseToCustomer
    }
  }

  private def handleReservation(inquiryId: Id[RideHailingInquiry], personId: Id[PersonAgent], customerPickUp: Location, destination: Location,
                                customerAgent: ActorRef, closestRideHailingAgentLocation: RideHailingAgentLocation, travelProposal: TravelProposal, tripPlan: Option[BeamTrip]) = {
    val confirmation = ReserveRideResponse(inquiryId, Right(RideHailConfirmData(closestRideHailingAgentLocation.rideHailAgent, personId, travelProposal)))
    triggerCustomerPickUp(customerPickUp, destination, closestRideHailingAgentLocation, tripPlan, confirmation)
    customerAgent ! confirmation
  }

  private def triggerCustomerPickUp(customerPickUp: Location, destination: Location, closestRideHailingAgentLocation: RideHailingAgentLocation, tripPlan: Option[BeamTrip], confirmation: ReserveRideResponse) = {
    closestRideHailingAgentLocation.rideHailAgent ! PickupCustomer(confirmation, customerPickUp, destination, tripPlan)
    inServiceRideHailVehicles.put(closestRideHailingAgentLocation.vehicleId, closestRideHailingAgentLocation)
    rideHailingAgent.remove(closestRideHailingAgentLocation.currentLocation.loc.getX, closestRideHailingAgentLocation.currentLocation.loc.getY, closestRideHailingAgentLocation)
  }

  private def findVehicle(resourceId: Id[Vehicle]): Option[Vehicle] = {
    info.fleet.get(resourceId)
  }

  override def findResource(resourceId: Id[Vehicle]): Option[ActorRef] = ???

}
