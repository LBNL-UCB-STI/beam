package beam.agentsim.agents.ridehail.allocation

import beam.agentsim.agents.modalbehaviors.DrivesVehicle.StopDrivingIfNoPassengerOnBoardReply
import beam.agentsim.agents.ridehail.RideHailManager.{BufferedRideHailRequestsTrigger, PoolingInfo, RideHailAgentLocation}
import beam.agentsim.agents.ridehail.{RideHailManager, RideHailRequest}
import beam.router.BeamRouter.{Location, RoutingRequest, RoutingResponse}
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person
import org.matsim.vehicles.Vehicle

import scala.collection.mutable

abstract class RideHailResourceAllocationManager(private val rideHailManager: RideHailManager) extends LazyLogging {


  private var bufferedRideHailRequests = Map[RideHailRequest,List[RoutingResponse]]()

  def respondToInquiry(inquiry: RideHailRequest): InquiryResponse = {
    rideHailManager.getClosestIdleRideHailAgent(
      inquiry.pickUpLocation,
      rideHailManager.radiusInMeters
    ) match {
      case Some(agentLocation) =>
        SingleOccupantQuoteAndPoolingInfo(agentLocation,None,None)
      case None =>
        NoVehiclesAvailable
    }
  }

  def addRequestToBuffer(request: RideHailRequest) = {
    bufferedRideHailRequests = bufferedRideHailRequests + (request -> List())
  }
  def addRouteForRequestToBuffer(request: RideHailRequest, routingResponse: RoutingResponse) = {
    bufferedRideHailRequests = bufferedRideHailRequests + (request -> (bufferedRideHailRequests(request) :+ routingResponse))
  }
  def removeRequestFromBuffer(request: RideHailRequest) = {
    bufferedRideHailRequests = bufferedRideHailRequests - request
  }

  def allocateVehiclesToCustomers(tick: Int): AllocationResponse = {
    allocateVehiclesToCustomers(tick, new AllocationRequests(bufferedRideHailRequests))
  }

  /*
   * This method is called in both contexts, either with a single allocation request or with a batch of requests
   * to be processed.
   */
  def allocateVehiclesToCustomers(tick: Int, vehicleAllocationRequest: AllocationRequests): AllocationResponse = {
    // closest request
    val responses = vehicleAllocationRequest.requests.map { case (request, routingResponses) =>
      rideHailManager.getClosestIdleRideHailAgent(
        request.pickUpLocation,
        rideHailManager.radiusInMeters
      ) match {
        case Some(agentLocation) =>
          (request -> VehicleMatchedToCustomers(agentLocation, None))
        case None =>
          (request -> NoVehicleAllocated)
      }
    }
    logger.trace("default implementation allocateVehiclesToCustomers executed")
    if(responses.isEmpty){
      NoRidesRequested
    }else{
      VehicleAllocations(responses)
    }
  }

  /*

   */
  def handleRideCancellationReply(reply: StopDrivingIfNoPassengerOnBoardReply): Unit = {
    logger.trace("default implementation handleRideCancellationReply executed")
  }

  /*
  This method is called periodically and allows the overwriting resource allocation module to specify which
  ridehail vehicle should be repositioned/redistributed to which location to better meet anticipated demand.
   */
  def repositionVehicles(tick: Double): Vector[(Id[Vehicle], Location)] = {
    logger.trace("default implementation repositionVehicles executed")
    Vector()
  }

  /*
  This method is called whenever a reservation is sucessfully completed. Overwrite this method if you need to process this info further.
  Use case: You want to overwrite a ride and make sure that it has been processed before cancelling it. Reason: If you cancel it during the reservation,
  the reservation will overwrite the cancellation.
   */
  def reservationCompletionNotice(personId: Id[Person], vehicleId: Id[Vehicle]): Unit = {}

}

object RideHailResourceAllocationManager {
  val DEFAULT_MANAGER = "DEFAULT_MANAGER"
  val EV_MANAGER = "EV_MANAGER"
  val IMMEDIATE_DISPATCH_WITH_OVERWRITE = "IMMEDIATE_DISPATCH_WITH_OVERWRITE"
  val POOLING = "POOLING"
  val REPOSITIONING_LOW_WAITING_TIMES = "REPOSITIONING_LOW_WAITING_TIMES"
  val RANDOM_REPOSITIONING = "RANDOM_REPOSITIONING"
  val DUMMY_DISPATCH_WITH_BUFFERING = "DUMMY_DISPATCH_WITH_BUFFERING"

  def apply(
    allocationManager: String,
    rideHailManager: RideHailManager
  ): RideHailResourceAllocationManager = {
    allocationManager match {
      case RideHailResourceAllocationManager.DEFAULT_MANAGER =>
        new DefaultRideHailResourceAllocationManager(rideHailManager)
      case RideHailResourceAllocationManager.EV_MANAGER =>
//        new EVFleetAllocationManager(rideHailManager)
        new DefaultRideHailResourceAllocationManager(rideHailManager)
      case RideHailResourceAllocationManager.POOLING =>
        new Pooling(rideHailManager)
      case RideHailResourceAllocationManager.REPOSITIONING_LOW_WAITING_TIMES =>
        new RepositioningLowWaitingTimes(rideHailManager)
      case RideHailResourceAllocationManager.RANDOM_REPOSITIONING =>
        new RandomRepositioning(rideHailManager)
      case classFullName =>
        try {
          Class
            .forName(classFullName)
            .getDeclaredConstructors()(0)
            .newInstance(rideHailManager)
            .asInstanceOf[RideHailResourceAllocationManager]
        } catch {
          case e: Exception =>
            throw new IllegalStateException(s"Unknown RideHailResourceAllocationManager: $allocationManager", e)
        }
    }
  }
}

/*
 * An InquiryResponse is how we respond to customer inquiries. This looks similar to AllocationResponse
 * except for a couple of difference:
 * 1) InquiryResponses are always assumed to contain a plan for a single occupant
 * ride hail trip plus PoolingInfo which gives relative time and cost estimate for a companion pooled ride quote.
 * 2) InquiryResponses are therefore one to one, response -> inquiry... whereas AllocationResponse
 * can be one to many... i.e. one vehicle is assigned to many customers.
 */
trait InquiryResponse
case object NoVehiclesAvailable extends InquiryResponse
case class SingleOccupantQuoteAndPoolingInfo(rideHailAgentLocation: RideHailAgentLocation,
                                             routingResponses: Option[List[RoutingResponse]],
                                             poolingInfo: Option[PoolingInfo]) extends InquiryResponse
/*
 * An AllocationResponse is what the RideHailResourceAllocationManager returns in response to an AllocationRequest
 */
trait AllocationResponse
case object NoRidesRequested extends AllocationResponse
case class VehicleAllocations(allocations: Map[RideHailRequest,VehicleAllocation]) extends AllocationResponse

/*
 * A VehicleAllocation is a specific directive about one ride hail vehicle
 * (match found or no match found? if found, who are the customers?)
 */
trait VehicleAllocation
case object NoVehicleAllocated extends VehicleAllocation
case class RoutingRequiredToAllocateVehicle(
                                             routesRequired: List[RoutingRequest]
                                           ) extends VehicleAllocation
case class VehicleMatchedToCustomers(
                                      rideHailAgentLocation: RideHailAgentLocation,
                                      routingResponses: Option[List[RoutingResponse]]
                                    ) extends VehicleAllocation


case class AllocationRequests(requests: Map[RideHailRequest, List[RoutingResponse]])
object AllocationRequests{
  def apply(requests: List[RideHailRequest]): AllocationRequests = AllocationRequests(requests.map( (_ -> List()) ).toMap)
  def apply(request: RideHailRequest): AllocationRequests = AllocationRequests(Map((request -> List())))
  def apply(request: RideHailRequest, routeResponses: List[RoutingResponse]): AllocationRequests = AllocationRequests(Map((request -> routeResponses)))
}

//requestType: RideHailRequestType,
//customer: VehiclePersonId,
//pickUpLocation: Location,
//departAt: BeamTime,
//destination: Location

// TODO (RW): mention to CS that cost removed from VehicleAllocationResult, as not needed to be returned (RHM default implementation calculates it already)
