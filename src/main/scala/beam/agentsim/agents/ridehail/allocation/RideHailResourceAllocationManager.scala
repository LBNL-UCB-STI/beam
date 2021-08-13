package beam.agentsim.agents.ridehail.allocation

import beam.agentsim.agents.ridehail.RideHailManager.PoolingInfo
import beam.agentsim.agents.ridehail.RideHailManagerHelper.RideHailAgentLocation
import beam.agentsim.agents.ridehail.repositioningmanager._
import beam.agentsim.agents.ridehail.{RideHailManager, RideHailRequest}
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.agents.{Dropoff, MobilityRequest, Pickup, Relocation}
import beam.router.BeamRouter
import beam.router.BeamRouter.{Location, RoutingRequest, RoutingResponse}
import beam.sim.BeamServices
import beam.sim.config.BeamConfig
import com.typesafe.scalalogging.LazyLogging
import enumeratum.{Enum, EnumEntry}
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person

abstract class RideHailResourceAllocationManager(private val rideHailManager: RideHailManager) extends LazyLogging {

  private var bufferedRideHailRequests = Map[RideHailRequest, List[RoutingResponse]]()
  private var secondaryBufferedRideHailRequests = Map[RideHailRequest, List[RoutingResponse]]()
  private var awaitingRoutes = Set[RideHailRequest]()

  protected val maxWaitTimeInSec =
    rideHailManager.beamScenario.beamConfig.beam.agentsim.agents.rideHail.allocationManager.maxWaitingTimeInSec

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
    rideHailManager.rideHailManagerHelper.getClosestIdleVehiclesWithinRadiusByETA(
      inquiry.pickUpLocationUTM,
      inquiry.destinationUTM,
      rideHailManager.radiusInMeters,
      maxWaitTimeInSec,
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
  def addRequestToBuffer(request: RideHailRequest): Unit = {
    bufferedRideHailRequests = bufferedRideHailRequests + (request -> List())
  }

  def addRequestToSecondaryBuffer(request: RideHailRequest): Unit = {
    secondaryBufferedRideHailRequests = secondaryBufferedRideHailRequests + (request -> List())
  }

  def clearPrimaryBufferAndFillFromSecondary: Unit = {
    bufferedRideHailRequests = secondaryBufferedRideHailRequests
    secondaryBufferedRideHailRequests = Map()
  }

  def getBufferSize: Int = bufferedRideHailRequests.size

  def addRouteForRequestToBuffer(request: RideHailRequest, routingResponse: RoutingResponse): Unit = {
    if (awaitingRoutes.contains(request)) awaitingRoutes -= request
    if (!bufferedRideHailRequests.contains(request)) addRequestToBuffer(request)
    bufferedRideHailRequests =
      bufferedRideHailRequests + (request -> (bufferedRideHailRequests(request) :+ routingResponse))
  }

  def removeRequestFromBuffer(request: RideHailRequest): Unit = {
    bufferedRideHailRequests -= request
  }
  def isBufferEmpty: Boolean = bufferedRideHailRequests.isEmpty

  /**
    * Take all requests in the buffer that are not part of the current product type (i.e. SOLO or POOLED) and move
    * them into the secondary request buffer which effectively delays when they are processed to a future allocation.
    *
    * @param dispatchProductType Enum specifying which product type is being processed.
    */
  private def moveNonMatchingDispatchProductTypeToSecondaryBuffer(dispatchProductType: DispatchProductType): Unit = {
    val requestsNotMatchingDispatchProductType = if (dispatchProductType == DispatchProductType.POOLED) {
      bufferedRideHailRequests.filterNot(x => x._1.asPooled)
    } else if (dispatchProductType == DispatchProductType.SOLO) {
      bufferedRideHailRequests.filter(x => x._1.asPooled)
    } else {
      Map.empty[RideHailRequest, scala.List[BeamRouter.RoutingResponse]]
    }

    requestsNotMatchingDispatchProductType.foreach { case (rideHailRequest, _) =>
      removeRequestFromBuffer(rideHailRequest)
      addRequestToSecondaryBuffer(rideHailRequest)
    }
  }

  def allocateVehiclesToCustomers(
    tick: Int,
    beamServices: BeamServices,
    dispatchProductType: DispatchProductType,
    triggerId: Long
  ): AllocationResponse = {
    moveNonMatchingDispatchProductTypeToSecondaryBuffer(dispatchProductType)

    val allocationResponse =
      allocateVehiclesToCustomers(tick, new AllocationRequests(bufferedRideHailRequests), beamServices, triggerId)
    allocationResponse match {
      case VehicleAllocations(allocations) =>
        allocations.foreach {
          case RoutingRequiredToAllocateVehicle(request, _) =>
            awaitingRoutes += request
            bufferedRideHailRequests -= request
          case _ =>
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
  def allocateVehiclesToCustomers(
    tick: Int,
    vehicleAllocationRequest: AllocationRequests,
    beamServices: BeamServices,
    triggerId: Long
  ): AllocationResponse = {
    // closest request
    var alreadyAllocated: Set[Id[BeamVehicle]] = Set()
    val allocResponses = vehicleAllocationRequest.requests.map {
      case (request, routingResponses) if routingResponses.isEmpty =>
        val requestWithUpdatedLoc = RideHailRequest.projectCoordinatesToUtm(request, beamServices)
        rideHailManager.rideHailManagerHelper
          .getClosestIdleVehiclesWithinRadiusByETA(
            requestWithUpdatedLoc.pickUpLocationUTM,
            requestWithUpdatedLoc.destinationUTM,
            rideHailManager.radiusInMeters,
            tick,
            maxWaitTimeInSec
          ) match {
          case Some(agentETA) =>
            val routeRequired = RoutingRequiredToAllocateVehicle(
              requestWithUpdatedLoc,
              rideHailManager.createRoutingRequestsToCustomerAndDestination(
                tick,
                requestWithUpdatedLoc,
                agentETA.agentLocation,
                request.triggerId
              )
            )
            routeRequired
          case None =>
            NoVehicleAllocated(requestWithUpdatedLoc)
        }
      // The following if condition ensures we actually got routes back in all cases
      case (request, routingResponses) if routingResponses.exists(_.itineraries.isEmpty) =>
        NoVehicleAllocated(request)
      case (request, routingResponses) =>
        val requestUpdated = RideHailRequest.projectCoordinatesToUtm(request, beamServices)
        rideHailManager.rideHailManagerHelper
          .getClosestIdleVehiclesWithinRadiusByETA(
            requestUpdated.pickUpLocationUTM,
            requestUpdated.destinationUTM,
            rideHailManager.radiusInMeters,
            tick,
            maxWaitTimeInSec,
            excludeRideHailVehicles = alreadyAllocated
          ) match {
          case Some(agentETA) =>
            alreadyAllocated = alreadyAllocated + agentETA.agentLocation.vehicleId
            val schedule = List(
              MobilityRequest.simpleRequest(
                Relocation,
                Some(requestUpdated.customer),
                routingResponses.head.itineraries.head.legs.headOption
              ),
              MobilityRequest
                .simpleRequest(
                  Pickup,
                  Some(requestUpdated.customer),
                  routingResponses.last.itineraries.head.legs.headOption
                ),
              MobilityRequest.simpleRequest(Dropoff, Some(requestUpdated.customer), None)
            )
            VehicleMatchedToCustomers(requestUpdated, agentETA.agentLocation, schedule)
          case None =>
            NoVehicleAllocated(requestUpdated)
        }
    }.toVector
    VehicleAllocations(allocResponses)
  }

  val repositioningManager: RepositioningManager = createRepositioningManager()
  logger.info(s"Using ${repositioningManager.getClass.getSimpleName} as RepositioningManager")

  /*
   * repositionVehicles
   *
   * This method is called periodically according to the parameter `beam.agentsim.agents.rideHail.repositioningManager.timeout`
   * The response of this method is used to reposition idle vehicles to new locations to better meet anticipated demand.
   * Currently it is not possible to enable repositioning AND batch allocation simultaneously. But simultaneous execution
   * will be enabled in the near-term.
   */
  def repositionVehicles(
    idleVehicles: scala.collection.Map[Id[BeamVehicle], RideHailAgentLocation],
    tick: Int
  ): Vector[(Id[BeamVehicle], Location)] = {

    repositioningManager.repositionVehicles(idleVehicles, tick)
  }

  /*
   * This method is called whenever a reservation is successfully completed. Override this method if you
   * need to to cleanup or take further action.
   * Use case: You want to overwrite a ride and make sure that it has been processed before cancelling it.
   * Reason: If you cancel it during the reservation, the reservation will overwrite the cancellation.
   */
  def reservationCompletionNotice(personId: Id[Person], vehicleId: Id[BeamVehicle]): Unit = {}

  def getUnprocessedCustomers: Set[RideHailRequest] = awaitingRoutes

  def createRepositioningManager(): RepositioningManager = {
    val repositioningManagerName =
      rideHailManager.beamServices.beamConfig.beam.agentsim.agents.rideHail.repositioningManager.name

    val repositionManagerTry = rideHailManager.beamServices.beamCustomizationAPI.getRepositionManagerFactory
      .create(rideHailManager, repositioningManagerName)

    repositionManagerTry.recoverWith { case exception: Exception =>
      throw new IllegalStateException(s"There is no implementation for `$repositioningManagerName`", exception)
    }.get
  }
}

object RideHailResourceAllocationManager {
  val DEFAULT_MANAGER = "DEFAULT_MANAGER"
  val EV_MANAGER = "EV_MANAGER"
  val IMMEDIATE_DISPATCH_WITH_OVERWRITE = "IMMEDIATE_DISPATCH_WITH_OVERWRITE"
  val POOLING = "POOLING"
  val POOLING_ALONSO_MORA = "POOLING_ALONSO_MORA"
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

  def requiredRideHailIterationsStatsCollector(rideHailConfig: BeamConfig.Beam.Agentsim.Agents.RideHail): Boolean =
    rideHailConfig.repositioningManager.name match {
      case "REPOSITIONING_LOW_WAITING_TIMES" => true
      case _                                 => false
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

case class VehicleAllocations(allocations: IndexedSeq[VehicleAllocation]) extends AllocationResponse

/*
 * A VehicleAllocation is a specific directive about one ride hail vehicle
 * (match found or no match found? if found, who are the customers?)
 */
trait VehicleAllocation {
  val request: RideHailRequest
}

case class NoVehicleAllocated(request: RideHailRequest) extends VehicleAllocation

case class RoutingRequiredToAllocateVehicle(request: RideHailRequest, routesRequired: List[RoutingRequest])
    extends VehicleAllocation

case class VehicleMatchedToCustomers(
  request: RideHailRequest,
  rideHailAgentLocation: RideHailAgentLocation,
  schedule: List[MobilityRequest]
) extends VehicleAllocation

case class AllocationRequests(requests: Map[RideHailRequest, List[RoutingResponse]])

object AllocationRequests {
  def apply(requests: List[RideHailRequest]): AllocationRequests = AllocationRequests(requests.map(_ -> List()).toMap)

  def apply(request: RideHailRequest): AllocationRequests = AllocationRequests(Map(request -> List()))

  def apply(request: RideHailRequest, routeResponses: List[RoutingResponse]): AllocationRequests =
    AllocationRequests(Map(request -> routeResponses))
}

sealed trait DispatchProductType extends EnumEntry

/**
  * Flags what product is being dispatched, either SOLO, POOLED, or both SOLO_AND_POOLED.
  */
object DispatchProductType extends Enum[DispatchProductType] {
  val values = findValues

  case object SOLO extends DispatchProductType

  case object SOLO_AND_POOLED extends DispatchProductType

  case object POOLED extends DispatchProductType

}
//requestType: RideHailRequestType,
//customer: VehiclePersonId,
//pickUpLocation: Location,
//departAt: BeamTime,
//destination: Location

// TODO (RW): mention to CS that cost removed from VehicleAllocationResult, as not needed to be returned (RHM default implementation calculates it already)
