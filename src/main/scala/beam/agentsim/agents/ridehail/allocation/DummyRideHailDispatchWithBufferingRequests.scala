package beam.agentsim.agents.ridehail.allocation

import beam.agentsim.agents.modalbehaviors.DrivesVehicle
import beam.agentsim.agents.ridehail.RideHailManager
import beam.router.BeamRouter.Location
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

class DummyRideHailDispatchWithBufferingRequests(val rideHailManager: RideHailManager)
    extends RideHailResourceAllocationManager(rideHailManager) {

  override def updateVehicleAllocations(tick: Double, triggerId: Long): Unit = ???

  override def handleRideCancellationReply(
    reply: DrivesVehicle.StopDrivingIfNoPassengerOnBoardReply
  ): Unit = ???

  // Only override proposeVehicleAllocation if you wish to do something different from closest euclidean vehicle
  //  override def proposeVehicleAllocation(vehicleAllocationRequest: VehicleAllocationRequest): VehicleAllocationResponse

  override def repositionVehicles(tick: Double): Vector[(Id[Vehicle], Location)] = ???
}
