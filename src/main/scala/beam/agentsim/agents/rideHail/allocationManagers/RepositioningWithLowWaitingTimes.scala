package beam.agentsim.agents.rideHail.allocationManagers

import beam.agentsim.agents.rideHail.RideHailingManager
import beam.router.BeamRouter.Location
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

class RepositioningWithLowWaitingTimes(val rideHailingManager: RideHailingManager) extends RideHailResourceAllocationManager {

  val isBufferedRideHailAllocationMode = false

  def proposeVehicleAllocation(vehicleAllocationRequest: VehicleAllocationRequest): Option[VehicleAllocation] = {
    None
  }

def allocateVehicles(allocationsDuringReservation: Vector[(VehicleAllocationRequest, Option[VehicleAllocation])]): IndexedSeq[(VehicleAllocationRequest, Option[VehicleAllocation])] = {
  log.error("batch processing is not implemented for DefaultRideHailResourceAllocationManager")
    return allocationsDuringReservation
  }

  override def repositionVehicles(tick: Double): Vector[(Id[Vehicle], Location)] = {
    if (rideHailingManager.getIdleVehicles().size >= 2) {
      val origin=rideHailingManager.getIdleVehicles().values.toVector
      val destination=scala.util.Random.shuffle(origin)
      (for ((o,d)<-(origin zip destination)) yield (o.vehicleId,d.currentLocation.loc)) //.splitAt(4)._1
    } else {
      Vector()
    }
  }
}




