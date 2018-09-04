package beam.agentsim.agents.ridehail.allocation

import beam.agentsim.agents.modalbehaviors.DrivesVehicle.StopDrivingIfNoPassengerOnBoardReply
import beam.agentsim.agents.rideHail.allocation.{EVFleetAllocationManager}
import beam.agentsim.agents.ridehail.{BufferedRideHailRequests, RideHailManager, RideHailRequest}
import beam.agentsim.agents.ridehail.RideHailManager.{BufferedRideHailRequestsTimeout, RideHailAgentLocation}
import beam.agentsim.agents.ridehail.{BufferedRideHailRequests, RideHailManager, RideHailRequest}
import beam.agentsim.scheduler.BeamAgentScheduler.ScheduleTrigger
import beam.router.BeamRouter.{Location, RoutingRequest, RoutingResponse}
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person
import org.matsim.vehicles.Vehicle

abstract class RideHailResourceAllocationManager(private val rideHailManager: RideHailManager)
    extends LazyLogging {

  val bufferedRideHailRequests: BufferedRideHailRequests = new BufferedRideHailRequests(
    rideHailManager.scheduler
  )

  def proposeVehicleAllocation(
    vehicleAllocationRequest: VehicleAllocationRequest
  ): VehicleAllocationResponse = {
    // closest request
    rideHailManager
      .getClosestIdleRideHailAgent(
        vehicleAllocationRequest.request.pickUpLocation,
        rideHailManager.radiusInMeters
      ) match {
      case Some(agentLocation) =>
        VehicleAllocation(agentLocation, None)
      case None =>
        NoVehicleAllocated
    }
  }

  // TODO: is third argument really needed
  def updateVehicleAllocations(
    tick: Double,
    triggerId: Long,
    rideHailManager: RideHailManager
  ): Unit = {
    bufferedRideHailRequests.newTimeout(tick, triggerId)

    updateVehicleAllocations(tick, triggerId)

    // TODO: refactor to BufferedRideHailRequests?
    val timerTrigger = BufferedRideHailRequestsTimeout(
      tick + 1 // TODO: replace with new config variable
    )
    val timerMessage = ScheduleTrigger(timerTrigger, rideHailManager.self)
    Vector(timerMessage)

    val nextMessage = Vector(timerMessage)

    bufferedRideHailRequests.addTriggerMessages(nextMessage)

    bufferedRideHailRequests.tryClosingBufferedRideHailRequestWaive()

  }

  /*
    This method is called periodically
   */
  def updateVehicleAllocations(tick: Double, triggerId: Long): Unit = {
    logger.trace("default implementation updateVehicleAllocations executed")
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
  val STANFORD_V1 = "STANFORD_V1"
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
        new EVFleetAllocationManager(rideHailManager)
      case RideHailResourceAllocationManager.STANFORD_V1 =>
        new StanfordRideHailAllocationManagerV1(rideHailManager)
      case RideHailResourceAllocationManager.REPOSITIONING_LOW_WAITING_TIMES =>
        new RepositioningLowWaitingTimes(rideHailManager)
      case RideHailResourceAllocationManager.RANDOM_REPOSITIONING =>
        new RandomRepositioning(rideHailManager)
      case RideHailResourceAllocationManager.IMMEDIATE_DISPATCH_WITH_OVERWRITE =>
        new ImmediateDispatchWithOverwrite(rideHailManager)
      case RideHailResourceAllocationManager.DUMMY_DISPATCH_WITH_BUFFERING =>
        new DummyRideHailDispatchWithBufferingRequests(rideHailManager)
      case x if x startsWith ("Test_") =>
        //var clazzExModule = classLoader.loadClass(Module.ModuleClassName + "$")
        //clazzExModule.getField("MODULE$").get(null).asInstanceOf[Module]

        val classFullName = x.replaceAll("Test_", "")
        Class
          .forName(classFullName)
          .getDeclaredConstructors()(0)
          .newInstance(rideHailManager)
          .asInstanceOf[RideHailResourceAllocationManager]

      case _ =>
        throw new IllegalStateException(
          s"Unknown RideHailResourceAllocationManager: $allocationManager"
        )
    }
  }
}

trait VehicleAllocationResponse

case class RoutingRequiredToAllocateVehicle(
  request: RideHailRequest,
  routesRequired: List[RoutingRequest]
) extends VehicleAllocationResponse
case class VehicleAllocation(
  rideHailAgentLocation: RideHailAgentLocation,
  routingResponses: Option[List[RoutingResponse]]
) extends VehicleAllocationResponse
case object NoVehicleAllocated extends VehicleAllocationResponse

case class VehicleAllocationRequest(
  request: RideHailRequest,
  routingResponses: List[RoutingResponse]
)

//requestType: RideHailRequestType,
//customer: VehiclePersonId,
//pickUpLocation: Location,
//departAt: BeamTime,
//destination: Location

// TODO (RW): mention to CS that cost removed from VehicleAllocationResult, as not needed to be returned (RHM default implementation calculates it already)
