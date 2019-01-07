package beam.agentsim.agents.ridehail.allocation

import beam.agentsim.agents.ridehail.RideHailManager
import beam.router.BeamRouter.Location
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

class RandomRepositioning(val rideHailManager: RideHailManager)
    extends RideHailResourceAllocationManager(rideHailManager) {

  // Only override proposeVehicleAllocation if you wish to do something different from closest euclidean vehicle
  //  override def proposeVehicleAllocation(vehicleAllocationRequest: VehicleAllocationRequest): VehicleAllocationResponse

  override def repositionVehicles(tick: Double): Vector[(Id[Vehicle], Location)] = {

    val repositioningShare =
      rideHailManager.beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.randomRepositioning.repositioningShare
    val fleetSize = rideHailManager.fleetSize
    val numVehiclesToReposition = (repositioningShare * fleetSize).toInt
    if (rideHailManager.vehicleManager.getIdleVehicles.size >= 2) {
      val origin = rideHailManager.vehicleManager.getIdleVehicles.values.toVector
      val destination = scala.util.Random.shuffle(origin)
      (for ((o, d) <- origin zip destination)
        yield (o.vehicleId, d.currentLocationUTM.loc))
        .splitAt(numVehiclesToReposition)
        ._1
    } else {
      Vector()
    }
  }
}
