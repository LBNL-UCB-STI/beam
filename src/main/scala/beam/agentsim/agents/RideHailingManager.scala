package beam.agentsim.agents

import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, Props}
import beam.agentsim.agents.BeamAgent.BeamAgentData
import beam.agentsim.agents.RideHailingManager._
import beam.agentsim.agents.util.{AggregatorFactory, SingleActorAggregationResult}
import beam.agentsim.agents.vehicles.VehicleManager
import beam.router.BeamRouter
import beam.router.BeamRouter.{RouteLocation, RoutingRequest, RoutingRequestParams, RoutingResponse}
import beam.router.Modes.BeamMode._
import beam.router.RoutingModel.BeamTime
import beam.sim.{BeamServices, HasServices}
import org.geotools.geometry.DirectPosition2D
import org.geotools.referencing.CRS
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.collections.QuadTree
import org.matsim.core.utils.geometry.CoordUtils
import org.matsim.vehicles.{Vehicle, VehicleType}
import org.slf4j.LoggerFactory

import scala.concurrent.duration.{Duration, FiniteDuration}


/**
  * BEAM
  */

object RideHailingManager {
  val log = LoggerFactory.getLogger(classOf[RideHailingManager])

  def nextTaxiInquiryId = Id.create(UUID.randomUUID().toString, classOf[RideHailingInquiry])

  case class RideHailingInquiry(inquiryId: Id[RideHailingInquiry], customerId: Id[PersonAgent], pickUpLocation: RouteLocation, departAt: BeamTime, radius: Double, destination: RouteLocation)

  case class TravelProposal(taxiAgentLocation: TaxiAgentLocation, timesToCustomer: Long, estimatedPrice: BigDecimal, estimatedTravelTime: Option[Duration])

  case class RideHailingInquiryResponse(inquiryId: Id[RideHailingInquiry], proposals: Vector[TravelProposal])

  case class ReserveTaxi(location: Coord)
  case class ReserveTaxiConfirmation(taxi: Option[ActorRef], timeToCustomer: Double)

  case class RegisterTaxiAvailable(taxiAgent: ActorRef,  vehicleId: Id[Vehicle],  location: Coord )
  case class RegisterTaxiUnavailable(ref: ActorRef, location: Coord )
  case object TaxiUnavailableAck
  case object TaxiAvailableAck

  case class TaxiAgentLocation(taxiAgent: ActorRef, vehicleId: Id[Vehicle], currentLocation: Coord)

  def props(name: String,fares: Map[Id[VehicleType], BigDecimal], fleet: Map[Id[Vehicle], Vehicle],  services: BeamServices) = {
    Props(classOf[RideHailingManager], RideHailingManagerData(name, fares, fleet), services)
  }
}
case class RideHailingManagerData(name: String, fares: Map[Id[VehicleType], BigDecimal],
                                  fleet: Map[Id[Vehicle], Vehicle]) extends BeamAgentData

class RideHailingManager(info: RideHailingManagerData, val beamServices: BeamServices) extends VehicleManager
  with HasServices with AggregatorFactory {
  import scala.collection.JavaConverters._
  val bbBuffer = 100000
  val DefaultCostPerMile = BigDecimal(beamServices.beamConfig.beam.taxi.defaultCostPerMile)

  val taxiAgentLocationIndex = new QuadTree[TaxiAgentLocation](beamServices.bbox.minX - bbBuffer,beamServices.bbox.minY - bbBuffer,beamServices.bbox.maxX + bbBuffer,beamServices.bbox.maxY + bbBuffer)
  val transform = CRS.findMathTransform(CRS.decode("EPSG:4326", true), CRS.decode(beamServices.beamConfig.beam.routing.gtfs.crs, true), false)

  override def receive: Receive = {
    case RegisterTaxiAvailable(taxiAgentRef: ActorRef, vehicleId: Id[Vehicle], location: Coord ) =>
      // register
      val pos = new DirectPosition2D(location.getX, location.getY)
      val posTransformed = new DirectPosition2D(location.getX,location.getY)
      if (location.getX <= 180.0 & location.getX >= -180.0 & location.getY > -90.0 & location.getY < 90.0) {
        transform.transform(pos, posTransformed)
      }
      taxiAgentLocationIndex.put(posTransformed.x,posTransformed.y, TaxiAgentLocation(taxiAgentRef, vehicleId, location))
      sender ! TaxiAvailableAck

    case inquiry@RideHailingInquiry(inquiryId, personId, customerPickUp, departAt, radius, destination) =>
      val nearByTaxiAgents = taxiAgentLocationIndex.getDisk(inquiry.pickUpLocation.getX, inquiry.pickUpLocation.getY, radius).asScala.toVector
      //PartialSort Knearest
      val params = RoutingRequestParams(departAt, Vector(CAR, TAXI), personId)
      val distances2taxi = nearByTaxiAgents.map(taxiAgentLocation => {
        val distance = CoordUtils.calcProjectedEuclideanDistance(inquiry.pickUpLocation, taxiAgentLocation.currentLocation)
        (taxiAgentLocation, distance)
      })
      val chosenTaxiLocationOpt = distances2taxi.sortBy(_._2).headOption
      val customerAgent = sender()
      chosenTaxiLocationOpt match {
        case Some((taxiLocation, shortDistanceToTaxi)) =>
          val customerTripRequestId = BeamRouter.nextId
          val taxi2CustomerRequestId = BeamRouter.nextId
          val routeRequests = Map(
            beamServices.beamRouter -> List(
              RoutingRequest(customerTripRequestId, customerPickUp, destination, params),
              RoutingRequest(taxi2CustomerRequestId, taxiLocation.currentLocation, customerPickUp, params))
          )
          aggregate(customerAgent, routeRequests) { case result: SingleActorAggregationResult =>
            val responses = result.mapListTo[RoutingResponse].map(res => (res.requestId, res)).toMap
            val (taxi2customerTripPlan, timeToCustomer) = responses(taxi2CustomerRequestId).itinerary.map(t => (t, t.totalTravelTime)).minBy(_._2)
            val taxiFare = findVehicle(taxiLocation.vehicleId).flatMap(vehicle => info.fares.get(vehicle.getType.getId)).getOrElse(DefaultCostPerMile)
            val (customerTripPlan, cost) = responses(taxi2CustomerRequestId).itinerary.map(t => (t, t.estimateCost(taxiFare).min)).minBy(_._2)
            //TODO: include customerTrip plan in response to reuse( as option BeamTrip can include createdTime to check if the trip plan is still va
            //TODO: we response with collection of TravelCost to be able to consolidate responses from different ride hailing companies
            log.debug(s"Found taxi $taxiLocation for inquiryId=$inquiryId within $shortDistanceToTaxi miles, timeToCustomer=$timeToCustomer" )

            val travelProposal = TravelProposal(taxiLocation, timeToCustomer, cost, Option(FiniteDuration(customerTripPlan.totalTravelTime, TimeUnit.SECONDS)))
            RideHailingInquiryResponse(inquiryId, Vector(travelProposal))
          }
        case None =>
          // no taxi available
          customerAgent ! RideHailingInquiryResponse(inquiryId, Vector())
      }
    case ReserveTaxi(pickupLocation: Coord) =>
      var chosenTaxi : Option[ActorRef] = None
      var timeToCustomer = 0.0
      //TODO there's probably a threshold above which no match is made
/*      if(taxiAgentLocationIndex.size() > 0){
        val taxiAgentLocation = taxiAgentLocationIndex.getClosest(pickupLocation.getX, pickupLocation.getY)
        taxiAgentLocation.taxiAgent ! PickupCustomer
        val coord : Coord = taxiToCoord(taxiAgentLocation)
        timeToCustomer = travelTimeToCustomerInSecPerMeter * math.sqrt(math.pow(coord.getX - pickupLocation.getX,2.0) + math.pow(coord.getY - pickupLocation.getY,2.0))
        taxiAgentLocationIndex.remove(coord.getX,coord.getY,taxiAgentLocation)
        taxiToCoord -= taxiAgentLocation
        chosenTaxi = Some(taxiAgentLocation)
      }*/
      sender ! ReserveTaxiConfirmation(chosenTaxi, timeToCustomer)

    case msg =>
      log.info(s"unknown message received by TaxiManager $msg")
  }

  private def findVehicle(resourceId: Id[Vehicle]): Option[Vehicle] = {
    info.fleet.get(resourceId)
  }

  override def findResource(resourceId: Id[Vehicle]): Option[ActorRef] = ???

}
