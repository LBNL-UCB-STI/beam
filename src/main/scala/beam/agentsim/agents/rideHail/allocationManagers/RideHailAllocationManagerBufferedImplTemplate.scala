package beam.agentsim.agents.rideHail.allocationManagers

import beam.agentsim.agents.rideHail.RideHailingManager
import beam.router.BeamRouter.Location
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

class RideHailAllocationManagerBufferedImplTemplate(val rideHailingManager: RideHailingManager) extends RideHailResourceAllocationManager {

  val isBufferedRideHailAllocationMode = false // TODO: this should be true - change back later!

  // TODO: no nested option returned
  def proposeVehicleAllocation(vehicleAllocationRequest: VehicleAllocationRequest): Option[VehicleAllocation] = {
    val rideHailingAgentLocation = rideHailingManager.getClosestIdleRideHailingAgent(vehicleAllocationRequest.pickUpLocation, RideHailingManager.radiusInMeters)

    rideHailingAgentLocation match {
      case Some((agentLocation, _)) => Some(VehicleAllocation(agentLocation.vehicleId, agentLocation.currentLocation))
      case None => None
    }

  }

// TODO: should we use normal without break
  // use lockVehicle
  def allocateVehicles(allocationsDuringReservation: Vector[(VehicleAllocationRequest, Option[VehicleAllocation])]): Vector[(VehicleAllocationRequest, Option[VehicleAllocation])] = {
/*
    var result = Map[Id[RideHailingInquiry], VehicleAllocation]()
    var alreadyUsedVehicles = collection.mutable.Set[Id[Vehicle]]()
    for ((rideHailingInquiry, vehicleAllocationRequest) <- allocationBatchRequest) {
      var vehicleAllocation: Option[VehicleAllocation] = None

      breakable {
        for ((rideHailingAgentLocation, distance) <- rideHailingManager.getClosestVehiclesWithinStandardRadius(vehicleAllocationRequest.pickUpLocation, rideHailingManager.radius)) {
          if (!alreadyUsedVehicles.contains(rideHailingAgentLocation.vehicleId)) {
            alreadyUsedVehicles.add(rideHailingAgentLocation.vehicleId)
            vehicleAllocation = Some(VehicleAllocation(rideHailingAgentLocation.vehicleId,rideHailingAgentLocation.currentLocation))
            break
          }
        }
      }

      vehicleAllocation match {
        case Some(vehicleAllocation) =>
          result += (rideHailingInquiry -> vehicleAllocation)
          rideHailingManager.lockVehicle(vehicleAllocation.vehicleId)
        case None => result += (rideHailingInquiry -> None)
      }
    }
    result
    */
    allocationsDuringReservation
  }

  override def repositionVehicles(tick: Double): Vector[(Id[Vehicle], Location)] = {
    if (rideHailingManager.getIdleVehicles().size >= 2) {
      val iter = rideHailingManager.getIdleVehicles().iterator
      val (vehicleIdA, vehicleLocationA) = iter.next()
      val (vehicleIdB, vehicleLocationB) = iter.next()
      Vector((vehicleIdA, vehicleLocationB.currentLocation.loc))
    } else {
      Vector()
    }


  }
}

