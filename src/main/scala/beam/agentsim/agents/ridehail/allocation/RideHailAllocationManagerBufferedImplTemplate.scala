package beam.agentsim.agents.ridehail.allocation

import beam.agentsim.agents.ridehail.RideHailManager
import beam.router.BeamRouter.Location
import beam.utils.DebugLib
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

class RideHailAllocationManagerBufferedImplTemplate(val rideHailManager: RideHailManager)
    extends RideHailResourceAllocationManager {

  val isBufferedRideHailAllocationMode = true

  def proposeVehicleAllocation(
    vehicleAllocationRequest: VehicleAllocationRequest
  ): Option[VehicleAllocation] = {
    // just go with closest request
    None
  }

// TODO: should we use normal without break
  // use lockVehicle
  def updateVehicleAllocations(
    allocationsDuringReservation: Vector[(VehicleAllocationRequest, Option[VehicleAllocation])]
  ): IndexedSeq[(VehicleAllocationRequest, Option[VehicleAllocation])] = {

    DebugLib.emptyFunctionForSettingBreakPoint()

    /*
    var result = Map[Id[RideHailInquiry], VehicleAllocation]()
    var alreadyUsedVehicles = collection.mutable.Set[Id[Vehicle]]()
    for ((rideHailInquiry, vehicleAllocationRequest) <- allocationBatchRequest) {
      var vehicleAllocation: Option[VehicleAllocation] = None

      breakable {
        for ((rideHailAgentLocation, distance) <- rideHailManager.getClosestVehiclesWithinStandardRadius(vehicleAllocationRequest.pickUpLocation, rideHailManager.radius)) {
          if (!alreadyUsedVehicles.contains(rideHailAgentLocation.vehicleId)) {
            alreadyUsedVehicles.add(rideHailAgentLocation.vehicleId)
            vehicleAllocation = Some(VehicleAllocation(rideHailAgentLocation.vehicleId,rideHailAgentLocation.currentLocation))
            break
          }
        }
      }

      vehicleAllocation match {
        case Some(vehicleAllocation) =>
          result += (rideHailInquiry -> vehicleAllocation)
          rideHailManager.lockVehicle(vehicleAllocation.vehicleId)
        case None => result += (rideHailInquiry -> None)
      }
    }
    result
     */
    allocationsDuringReservation
  }

  override def repositionVehicles(tick: Double): Vector[(Id[Vehicle], Location)] = {
    if (rideHailManager.getIdleVehicles.size >= 2) {
      val iter = rideHailManager.getIdleVehicles.iterator
      val (vehicleIdA, _) = iter.next()
      val (_, vehicleLocationB) = iter.next()
      Vector((vehicleIdA, vehicleLocationB.currentLocation.loc))
    } else {
      Vector()
    }

  }
}
