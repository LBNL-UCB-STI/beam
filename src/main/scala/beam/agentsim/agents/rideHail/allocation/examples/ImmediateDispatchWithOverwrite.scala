package beam.agentsim.agents.rideHail.allocation.examples

import beam.agentsim.agents.modalbehaviors.DrivesVehicle.StopDrivingIfNoPassengerOnBoardReply
import beam.agentsim.agents.ridehail.RideHailManager
import beam.agentsim.agents.ridehail.allocation.{
  RideHailResourceAllocationManager,
  VehicleAllocation,
  VehicleAllocationRequest
}
import beam.router.BeamRouter.Location
import beam.router.RoutingModel.DiscreteTime
import beam.utils.DebugLib
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

import scala.collection.mutable

class ImmediateDispatchWithOverwrite(val rideHailManager: RideHailManager)
    extends RideHailResourceAllocationManager(rideHailManager) {

  var bufferedRideHailRequestsQueue = new mutable.Queue[VehicleAllocationRequest]

  override def proposeVehicleAllocation(
    vehicleAllocationRequest: VehicleAllocationRequest
  ): Option[VehicleAllocation] = {

    if (bufferedRideHailRequestsQueue.size < 1) {
      bufferedRideHailRequestsQueue += vehicleAllocationRequest
    }

    // just go with closest request
    None
  }

  override def handleRideCancellationReply(reply: StopDrivingIfNoPassengerOnBoardReply): Unit = {

    bufferedRideHailRequests.decreaseNumberOfOpenOverwriteRequests()

    if (reply.success) {
      val firstRequestOfDay = bufferedRideHailRequestsQueue.head

      val rhl = rideHailManager
        .getClosestIdleRideHailAgent(
          firstRequestOfDay.pickUpLocation,
          rideHailManager.radiusInMeters
        )
        .get

      val request = firstRequestOfDay.request.copy(
        departAt = DiscreteTime(bufferedRideHailRequests.getTick().toInt)
      )

      rideHailManager.requestRoutesToCustomerAndDestination(
        request,
        rhl
      )

      println(
        s" new vehicle assigned:${rhl.vehicleId}, tick: ${reply.tick}"
      )

      bufferedRideHailRequests.registerVehicleAsReplacementVehicle(rhl.vehicleId)

    } else {
      bufferedRideHailRequests.tryClosingBufferedRideHailRequestWaive()
    }

    bufferedRideHailRequestsQueue = new mutable.Queue[VehicleAllocationRequest]

  }

  // TODO: define 3 state names to allow for proper transitioning

  override def updateVehicleAllocations(tick: Double, triggerId: Long): Unit = {
    // try to cancel first ride of day

    if (!bufferedRideHailRequestsQueue.isEmpty) {
      val firstRequestOfDay = bufferedRideHailRequestsQueue.head

      logger.debug(
        s"trying to reassign vehicle to customer:${firstRequestOfDay.request.customer}, tick: $tick"
      )
      rideHailManager.attemptToCancelCurrentRideRequest(tick, firstRequestOfDay.request.requestId) // CONTINUE HERE
      logger.debug(
        s"attempt finished, tick: $tick"
      )

      // TODO: ask vehicle, if customer already picked up (can't rely on tick, as RHM tick might be in same window as driver pickup).
      //  -> make method custom
      //
      // , if not, cancel it (go to idle state properly) - make new request type for this?
      // let us lock the other ride and unlock if already picked up, otherwise dispatch it to customer

      bufferedRideHailRequests.increaseNumberOfOpenOverwriteRequests()
    }

  }

}
