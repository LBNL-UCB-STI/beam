package beam.agentsim.agents.ridehail.allocation

import beam.agentsim.agents.modalbehaviors.DrivesVehicle.StopDrivingIfNoPassengerOnBoardReply
import beam.agentsim.agents.ridehail.RideHailManager.{BufferedRideHailRequestsTrigger, PoolingInfo, RideHailAgentLocation}
import beam.agentsim.agents.ridehail.{RideHailManager, RideHailRequest}
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.router.BeamRouter.{Location, RoutingRequest, RoutingResponse}
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person
import org.matsim.vehicles.Vehicle

import scala.collection.mutable

abstract class RideHailResourceAllocationManager(private val rideHailManager: RideHailManager) extends LazyLogging {

  private val bufferedRideHailRequests = mutable.Set[RideHailRequest]()

  def allocateVehicle(vehicleAllocationRequest: AllocationRequests): AllocationResponses = {
    //We only allocate one vehicle in this method
    assert(vehicleAllocationRequest.requests.size==1)
    allocateVehicleToCustomer(vehicleAllocationRequest)
  }

  def allocateVehicleToCustomer(
    vehicleAllocationRequest: AllocationRequests
  ): AllocationResponses = {
    val request = vehicleAllocationRequest.requests.keys.head
    // closest request
    AllocationResponses(request,rideHailManager
      .getClosestIdleRideHailAgent(
        request.pickUpLocation,
        rideHailManager.radiusInMeters
      ) match {
      case Some(agentLocation) =>
        VehicleAllocation(agentLocation, None, None)
      case None =>
        NoVehicleAllocated
    })
  }

  def addRequestToBuffer(request: RideHailRequest) = {
    bufferedRideHailRequests.add(request)
  }

  def batchAllocateVehiclesToCustomers(tick: Int): AllocationResponses = {
    batchAllocateVehiclesToCustomers(tick, AllocationRequests(bufferedRideHailRequests.toList))
  }

  /*
    This method is called periodically
   */
  def batchAllocateVehiclesToCustomers(tick: Int, vehicleAllocationRequest: AllocationRequests): AllocationResponses = {
    logger.trace("default implementation proposeBatchedVehicleAllocations executed")
    AllocationResponses(vehicleAllocationRequest.requests.keys.map((_ -> NoVehicleAllocated)).toMap)
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

trait VehicleAllocationResponse

case class RoutingRequiredToAllocateVehicle(
  routesRequired: List[RoutingRequest]
) extends VehicleAllocationResponse

case class VehicleAllocation(
  rideHailAgentLocation: RideHailAgentLocation,
  routingResponses: Option[List[RoutingResponse]],
  poolingInfo: Option[PoolingInfo]
) extends VehicleAllocationResponse

case class AllocationResponses(allocations: Map[RideHailRequest,VehicleAllocationResponse])

object AllocationResponses{
  def apply(request: RideHailRequest,
            response: VehicleAllocationResponse): AllocationResponses = AllocationResponses(Map(request -> response))
}

case object NoVehicleAllocated extends VehicleAllocationResponse

case object NoRideRequested extends VehicleAllocationResponse

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
