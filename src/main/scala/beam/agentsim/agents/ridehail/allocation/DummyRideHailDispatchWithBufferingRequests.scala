package beam.agentsim.agents.ridehail.allocation

import beam.agentsim.agents.modalbehaviors.DrivesVehicle
import beam.agentsim.agents.ridehail.RideHailManager
import beam.router.BeamRouter.Location
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

class DummyRideHailDispatchWithBufferingRequests(val rideHailManager: RideHailManager)
    extends RideHailResourceAllocationManager {

  override def updateVehicleAllocations(tick: Double): Unit = ???

  override def handleRideCancellationReply(
    reply: DrivesVehicle.StopDrivingIfNoPassengerOnBoardReply
  ): Unit = ???

  override def proposeVehicleAllocation(
    vehicleAllocationRequest: VehicleAllocationRequest
  ): Option[VehicleAllocation] = {
    //Some(
    //  VehicleAllocation(RideHailManager.dummyRideHailVehicleId, vehicleAllocationRequest.departAt)
    //)
    ???
  }

  override def repositionVehicles(tick: Double): Vector[(Id[Vehicle], Location)] = ???
}
