package beam.agentsim.agents.ridehail.allocation

import beam.agentsim.agents.modalbehaviors.DrivesVehicle
import beam.agentsim.agents.ridehail.RideHailManager
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.Location
import beam.router.RoutingModel.DiscreteTime
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.vehicles.Vehicle

class DummyRideHailDispatchWithBufferingRequests(val rideHailManager: RideHailManager)
    extends RideHailResourceAllocationManager(rideHailManager) {

  override def proposeVehicleAllocation(
    vehicleAllocationRequest: VehicleAllocationRequest
  ): Option[VehicleAllocation] = {

    if (rideHailManager.getPendingDummyRequests.size < 5) {
      rideHailManager.markAsDummyRidehailRequest(vehicleAllocationRequest.request)
    }
    // mark request as dummy ride hail dispatch (later over write possible)
    None
  }

  override def updateVehicleAllocations(tick: Double, triggerId: Long): Unit = {
    // TODO: MISSING: scheduling modify passenger ack messages from vehicle

    for (request <- rideHailManager.getCompletedDummyRequests.values) {
      rideHailManager
        .getClosestIdleRideHailAgent(
          request.pickUpLocation,
          rideHailManager.radiusInMeters
        ) match {

        case Some(rhl) =>
          val updatedRequest = request.copy(
            departAt = DiscreteTime(tick.toInt)
          )

          rideHailManager.requestRoutesToCustomerAndDestination(
            request,
            rhl
          )

          println(
            s" new vehicle assigned:${rhl.vehicleId}, tick: ${tick}, person: ${request.customer.personId}"
          )

          rideHailManager.removeDummyRequest(request)
        case None =>
      }
      //bufferedRideHailRequests.registerVehicleAsReplacementVehicle(rhl.vehicleId)

    }

  }

//  override def handleRideCancellationReply(
  //   reply: DrivesVehicle.StopDrivingIfNoPassengerOnBoardReply
  // ): Unit = ???

}
