package beam.agentsim.agents.rideHail

import beam.agentsim.agents.rideHail.RideHailingManager.{ReserveRide, RideHailingAgentLocation, RideHailingInquiry}
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.Location
import beam.router.RoutingModel.BeamTime
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.geometry.CoordUtils
import org.matsim.vehicles.Vehicle
import scala.util.control.Breaks._

class RideHailAllocationManagerBufferedImplTemplate(val rideHailingManager: RideHailingManager) extends RideHailResourceAllocationManager {

  val isBufferedRideHailAllocationMode = true

  // TODO: no nested option returned
  def proposeVehicleAllocation(vehicleAllocationRequest: VehicleAllocationRequest): Option[VehicleAllocation] = {
    val rideHailingAgentLocation = rideHailingManager.getClosestRideHailingAgent(vehicleAllocationRequest.pickUpLocation, rideHailingManager.radius)

    rideHailingAgentLocation match {
      case Some((rideHailingAgentLocation, distance)) => Some(VehicleAllocation(rideHailingAgentLocation.vehicleId, rideHailingAgentLocation.currentLocation))
      case None => None
    }

  }

// TODO: should we use normal without break
  // use lockVehicle
  def allocateVehicles(allocationsDuringReservation: Map[Id[RideHailingInquiry], Option[(VehicleAllocation, RideHailingAgentLocation)]]): Map[Id[RideHailingInquiry], Option[VehicleAllocation]] = {
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
    ???
  }

  override def repositionVehicles(tick: Double): Vector[(Id[Vehicle], SpaceTime)] = {
    ???
  }
}

