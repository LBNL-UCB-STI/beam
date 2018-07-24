package beam.agentsim.agents.ridehail.allocationManagers

import beam.agentsim.agents.ridehail.RideHailManager
import beam.router.BeamRouter.Location
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

class RandomRepositioning(val rideHailManager: RideHailManager)
    extends RideHailResourceAllocationManager {

  val isBufferedRideHailAllocationMode = false

  def proposeVehicleAllocation(
    vehicleAllocationRequest: VehicleAllocationRequest
  ): Option[VehicleAllocation] = {
    None
  }

  def allocateVehicles(
    allocationsDuringReservation: Vector[(VehicleAllocationRequest, Option[VehicleAllocation])]
  ): IndexedSeq[(VehicleAllocationRequest, Option[VehicleAllocation])] = {
    log.error("batch processing is not implemented for DefaultRideHailResourceAllocationManager")
    allocationsDuringReservation
  }

  override def repositionVehicles(tick: Double): Vector[(Id[Vehicle], Location)] = {

    val repositioningShare =
      rideHailManager.beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.randomRepositioning.repositioningShare
    val fleetSize = rideHailManager.resources.size
    val numVechilesToReposition = (repositioningShare * fleetSize).toInt
    if (rideHailManager.getIdleVehicles.size >= 2) {
      val origin = rideHailManager.getIdleVehicles.values.toVector
      val destination = scala.util.Random.shuffle(origin)
      (for ((o, d) <- origin zip destination)
        yield (o.vehicleId, d.currentLocation.loc)).splitAt(numVechilesToReposition)._1
    } else {
      Vector()
    }
  }
}
