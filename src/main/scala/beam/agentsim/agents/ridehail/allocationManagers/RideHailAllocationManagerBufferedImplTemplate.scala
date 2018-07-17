package beam.agentsim.agents.ridehail.allocationManagers

import beam.agentsim.agents.ridehail.RideHailManager
import beam.router.BeamRouter.Location
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

class RideHailAllocationManagerBufferedImplTemplate(val rideHailManager: RideHailManager) extends RideHailResourceAllocationManager {

  val isBufferedRideHailAllocationMode = false // TODO: this should be true - change back later!

  // TODO: no nested option returned
  def proposeVehicleAllocation(vehicleAllocationRequest: VehicleAllocationRequest): Option[VehicleAllocation] = {
    val rideHailAgentLocation = rideHailManager.getClosestIdleRideHailAgent(vehicleAllocationRequest.pickUpLocation, rideHailManager.radiusInMeters)

    rideHailAgentLocation match {
      case Some(agentLocation) => Some(VehicleAllocation(agentLocation.vehicleId, agentLocation.currentLocation))
      case None => None
    }

  }

// TODO: should we use normal without break
  // use lockVehicle
  def allocateVehicles(allocationsDuringReservation: Vector[(VehicleAllocationRequest, Option[VehicleAllocation])]): IndexedSeq[(VehicleAllocationRequest, Option[VehicleAllocation])] = {
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

