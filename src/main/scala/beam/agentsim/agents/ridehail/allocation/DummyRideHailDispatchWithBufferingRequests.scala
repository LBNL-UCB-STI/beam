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

  override def updateVehicleAllocations(tick: Double, triggerId: Long): Unit = {

    // TODO: danger if we use updateVehicleAllocations/handleRideCancellationReply mechanism as well, this won't work
    // also make sure: within scheduler window, this shouldn't be called twice to avoid iddues -> how to resolve?
    bufferedRideHailRequests.newTimeout(tick, triggerId)

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
            updatedRequest,
            rhl
          )

          println(
            s" new vehicle assigned:${rhl.vehicleId}, tick: ${tick}, person: ${request.customer.personId}"
          )

          rideHailManager.removeDummyRequest(request)

          bufferedRideHailRequests.registerVehicleAsReplacementVehicle(rhl.vehicleId)
        case None =>
      }
      //bufferedRideHailRequests.registerVehicleAsReplacementVehicle(rhl.vehicleId)

    }

  }

//  override def handleRideCancellationReply(
  //   reply: DrivesVehicle.StopDrivingIfNoPassengerOnBoardReply
  // ): Unit = ???

}
