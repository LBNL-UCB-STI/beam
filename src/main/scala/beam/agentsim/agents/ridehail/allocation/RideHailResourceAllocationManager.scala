package beam.agentsim.agents.ridehail.allocation

import beam.agentsim.agents.modalbehaviors.DrivesVehicle.StopDrivingIfNoPassengerOnBoardReply
import beam.agentsim.agents.ridehail.RideHailManager.PoolingInfo
import beam.agentsim.agents.ridehail.RideHailVehicleManager.RideHailAgentLocation
import beam.agentsim.agents.ridehail.repositioningmanager.{
  DemandFollowingRepositioningManager,
  NoOpRepositioningManager,
  RepositioningLowWaitingTimes,
  RepositioningManager
}
import beam.agentsim.agents.ridehail.{RideHailManager, RideHailRequest}
import beam.agentsim.agents.vehicles.PersonIdWithActorRef
import beam.router.BeamRouter.{Location, RoutingRequest, RoutingResponse}
import beam.router.model.EmbodiedBeamLeg
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person
import org.matsim.vehicles.Vehicle

import scala.util.control.NonFatal

abstract class RideHailResourceAllocationManager(private val rideHailManager: RideHailManager) extends LazyLogging {

  private var bufferedRideHailRequests = Map[RideHailRequest, List[RoutingResponse]]()
  private var secondaryBufferedRideHailRequests = Map[RideHailRequest, List[RoutingResponse]]()
  private var awaitingRoutes = Set[RideHailRequest]()

  /*
   * respondToInquiry
   *
   * This method is called for every customer who inquires about ride hailing services. For simplicity and
   * performance reasons, we only need to return an agentLocation which is used by the RideHailManager to
   * calculate a route and ultimately a travel proposal that assumes a single occupant ride.
   *
   * If the allocation manager has pooling enabled as an option, then this method should also return
   * Some(poolingInfo) which contains simple multipliers to quote the average travel time increase
   * and price decrease that the customer would pay to elect for a pooled ride. You should use an average
   * travel time increase here and not a maximum increase in order to allow the passengers make long-term
   * rational choices about mode that reflect the true travel time cost of pooling.
   */
  def respondToInquiry(inquiry: RideHailRequest): InquiryResponse = {
    rideHailManager.vehicleManager.getClosestIdleVehiclesWithinRadiusByETA(
      inquiry.pickUpLocationUTM,
      inquiry.destinationUTM,
      rideHailManager.radiusInMeters,
      inquiry.departAt
    ) match {
      case Some(agentETA) =>
        SingleOccupantQuoteAndPoolingInfo(agentETA.agentLocation, None)
      case None =>
        NoVehiclesAvailable
    }
  }

  /*
   * The allocation manager maintains a buffer of ride hail requests that are then dispatched in batch
   * when the allocateVehiclesToCustomers method (below) is called. Your implementation of this manager
   * may need to optionally add or remove requests depending on your algorithm for allocation.
   *
   * See Pooling for an example of an algorithm that uses removeRequestFromBuffer
   */
  def addRequestToBuffer(request: RideHailRequest) = {
    bufferedRideHailRequests = bufferedRideHailRequests + (request -> List())
  }

  def addRequestToSecondaryBuffer(request: RideHailRequest) = {
    secondaryBufferedRideHailRequests = secondaryBufferedRideHailRequests + (request -> List())
  }

  def clearPrimaryBufferAndFillFromSecondary = {
    bufferedRideHailRequests = secondaryBufferedRideHailRequests
    secondaryBufferedRideHailRequests = Map()
  }

  def getBufferSize = bufferedRideHailRequests.size

  def addRouteForRequestToBuffer(request: RideHailRequest, routingResponse: RoutingResponse) = {
    if (awaitingRoutes.contains(request)) awaitingRoutes -= request
    if (!bufferedRideHailRequests.contains(request)) addRequestToBuffer(request)
    bufferedRideHailRequests = bufferedRideHailRequests + (request -> (bufferedRideHailRequests(request) :+ routingResponse))
  }

  def removeRequestFromBuffer(request: RideHailRequest) = {
    bufferedRideHailRequests -= request
  }
  def isBufferEmpty = bufferedRideHailRequests.isEmpty

  def allocateVehiclesToCustomers(tick: Int): AllocationResponse = {
    var allocationResponse = allocateVehiclesToCustomers(tick, new AllocationRequests(bufferedRideHailRequests))
    allocationResponse match {
      case VehicleAllocations(allocations) =>
        allocations.foreach { alloc =>
          alloc match {
            case RoutingRequiredToAllocateVehicle(request, _) =>
              awaitingRoutes += request
              bufferedRideHailRequests -= request
            case _ =>
          }
        }
      case _ =>
    }
    allocationResponse
  }

  /*
   * allocateVehiclesToCustomers
   *
   * This method is called in two contexts, either with a single allocation request or with a batch of requests
   * to be processed. The default implementation here uses a greedy, closest vehicle approach and only
   * allocates single-occupant rides. For pooled rides, you will need to use the "Pooling" allocation manager
   * or a variation on that manager.
   *
   * This method is designed to be called multiple times for every batch of allocations. Take a single request
   * as a simple example. If AllocationRequests contains only one request, the process flow will look like this:
   *
   * --- allocateVehiclesToCustomers is called with an AllocationRequest that contains request info but no route
   * --- the response to the above call is a VehicleAllocations object containing a RoutingRequiredToAllocateVehicle
   * --- the Ride Hail Manager will calculate the route and then call this method again, now with a request AND a route
   * --- based on the request and routing info, a final allocation will be created in the form of a VehicleMatchedToCustomers object
   * --- exceptions to the above is if an allocation can't be made in which case a NoVehicleAllocated response is returned
   *
   * The above process flow is identical for a batch of multiple requests, except that now the AllocationRequests and the VehicleAllocations
   * objects contain multiple requests and responses.
   */
  def allocateVehiclesToCustomers(tick: Int, vehicleAllocationRequest: AllocationRequests): AllocationResponse = {
    // closest request
    var alreadyAllocated: Set[Id[Vehicle]] = Set()
    val allocResponses = vehicleAllocationRequest.requests.map {
      case (request, routingResponses) if (routingResponses.isEmpty) =>
        rideHailManager.vehicleManager
          .getClosestIdleVehiclesWithinRadiusByETA(
            request.pickUpLocationUTM,
            request.destinationUTM,
            rideHailManager.radiusInMeters,
            tick
          ) match {
          case Some(agentETA) =>
            val routeRequired = RoutingRequiredToAllocateVehicle(
              request,
              rideHailManager.createRoutingRequestsToCustomerAndDestination(
                tick,
                request,
                agentETA.agentLocation
              )
            )
            routeRequired
          case None =>
            NoVehicleAllocated(request)
        }
      // The following if condition ensures we actually got routes back in all cases
      case (request, routingResponses) if routingResponses.find(_.itineraries.isEmpty).isDefined =>
        NoVehicleAllocated(request)
      case (request, routingResponses) =>
        rideHailManager.vehicleManager
          .getClosestIdleVehiclesWithinRadiusByETA(
            request.pickUpLocationUTM,
            request.destinationUTM,
            rideHailManager.radiusInMeters,
            tick,
            excludeRideHailVehicles = alreadyAllocated
          ) match {
          case Some(agentETA) =>
            alreadyAllocated = alreadyAllocated + agentETA.agentLocation.vehicleId
            val pickDropIdAndLegs = List(
              PickDropIdAndLeg(Some(request.customer), routingResponses.head.itineraries.head.legs.headOption),
              PickDropIdAndLeg(Some(request.customer), routingResponses.last.itineraries.head.legs.headOption)
            )
            VehicleMatchedToCustomers(request, agentETA.agentLocation, pickDropIdAndLegs)
          case None =>
            NoVehicleAllocated(request)
        }
    }.toList
    VehicleAllocations(allocResponses)
  }

  val repositioningManager: RepositioningManager = createRepositioningManager()
  logger.info(s"Using ${repositioningManager.getClass.getSimpleName} as RepositioningManager")

  /*
   * repositionVehicles
   *
   * This method is called periodically according to the parameter beam.agentsim.agents.rideHail.allocationManager.repositionTimeoutInSeconds
   * The response of this method is used to reposition idle vehicles to new locations to better meet anticipated demand.
   * Currently it is not possible to enable repositioning AND batch allocation simultaneously. But simultaneous execution
   * will be enabled in the near-term.
   */
  def repositionVehicles(tick: Int): Vector[(Id[Vehicle], Location)] = {
    repositioningManager.repositionVehicles(tick)
  }

  /*
   * This method is called whenever a reservation is successfully completed. Override this method if you
   * need to to cleanup or take further action.
   * Use case: You want to overwrite a ride and make sure that it has been processed before cancelling it.
   * Reason: If you cancel it during the reservation, the reservation will overwrite the cancellation.
   */
  def reservationCompletionNotice(personId: Id[Person], vehicleId: Id[Vehicle]): Unit = {}

  def getUnprocessedCustomers: Set[RideHailRequest] = awaitingRoutes

  /*
   * This is deprecated.
   */
  def handleRideCancellationReply(reply: StopDrivingIfNoPassengerOnBoardReply): Unit = {
    logger.trace("default implementation handleRideCancellationReply executed")
  }

  def createRepositioningManager(): RepositioningManager = {
    val name = rideHailManager.beamServices.beamConfig.beam.agentsim.agents.rideHail.repositioningManager.name
    try {
      name match {
        case "NoOpRepositioningManager" =>
          RepositioningManager[NoOpRepositioningManager](rideHailManager.beamServices, rideHailManager)
        case "DemandFollowingRepositioningManager" =>
          RepositioningManager[DemandFollowingRepositioningManager](rideHailManager.beamServices, rideHailManager)
        case "RepositioningLowWaitingTimes" =>
          RepositioningManager[RepositioningLowWaitingTimes](rideHailManager.beamServices, rideHailManager)
        case x =>
          throw new IllegalStateException(s"There is no implementation for `$x`")
      }
    } catch {
      case NonFatal(ex) =>
        logger.error(s"Could not create reposition manager from $name: ${ex.getMessage}", ex)
        throw ex
    }
  }
}

object RideHailResourceAllocationManager {
  val DEFAULT_MANAGER = "DEFAULT_MANAGER"
  val EV_MANAGER = "EV_MANAGER"
  val IMMEDIATE_DISPATCH_WITH_OVERWRITE = "IMMEDIATE_DISPATCH_WITH_OVERWRITE"
  val POOLING = "POOLING"
  val POOLING_ALONSO_MORA = "POOLING_ALONSO_MORA"
  val REPOSITIONING_LOW_WAITING_TIMES = "REPOSITIONING_LOW_WAITING_TIMES"
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
      case RideHailResourceAllocationManager.POOLING_ALONSO_MORA =>
        new PoolingAlonsoMora(rideHailManager)
      case RideHailResourceAllocationManager.REPOSITIONING_LOW_WAITING_TIMES =>
        ???
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
case class SingleOccupantQuoteAndPoolingInfo(
  rideHailAgentLocation: RideHailAgentLocation,
  poolingInfo: Option[PoolingInfo]
) extends InquiryResponse
/*
 * An AllocationResponse is what the RideHailResourceAllocationManager returns in response to an AllocationRequest
 */
trait AllocationResponse
case object NoRidesRequested extends AllocationResponse
case class VehicleAllocations(allocations: List[VehicleAllocation]) extends AllocationResponse

/*
 * A VehicleAllocation is a specific directive about one ride hail vehicle
 * (match found or no match found? if found, who are the customers?)
 */
trait VehicleAllocation { val request: RideHailRequest }
case class NoVehicleAllocated(request: RideHailRequest) extends VehicleAllocation
case class RoutingRequiredToAllocateVehicle(request: RideHailRequest, routesRequired: List[RoutingRequest])
    extends VehicleAllocation
case class VehicleMatchedToCustomers(
  request: RideHailRequest,
  rideHailAgentLocation: RideHailAgentLocation,
  pickDropIdWithRoutes: List[PickDropIdAndLeg]
) extends VehicleAllocation
case class PickDropIdAndLeg(personId: Option[PersonIdWithActorRef], leg: Option[EmbodiedBeamLeg])

case class AllocationRequests(requests: Map[RideHailRequest, List[RoutingResponse]])

object AllocationRequests {
  def apply(requests: List[RideHailRequest]): AllocationRequests = AllocationRequests(requests.map((_ -> List())).toMap)
  def apply(request: RideHailRequest): AllocationRequests = AllocationRequests(Map((request -> List())))

  def apply(request: RideHailRequest, routeResponses: List[RoutingResponse]): AllocationRequests =
    AllocationRequests(Map((request -> routeResponses)))
}

//requestType: RideHailRequestType,
//customer: VehiclePersonId,
//pickUpLocation: Location,
//departAt: BeamTime,
//destination: Location

// TODO (RW): mention to CS that cost removed from VehicleAllocationResult, as not needed to be returned (RHM default implementation calculates it already)
