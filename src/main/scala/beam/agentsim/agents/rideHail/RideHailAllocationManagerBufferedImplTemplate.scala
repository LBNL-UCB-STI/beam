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

  def getVehicleAllocation(pickUpLocation: Location, departAt: BeamTime, destination: Location): Option[VehicleAllocationResult] = {
    val rideHailingAgentLocation = rideHailingManager.getClosestRideHailingAgent(pickUpLocation, rideHailingManager.radius)

    rideHailingAgentLocation match {
      case Some((rideHailingAgentLocation, distance)) => Some(VehicleAllocationResult(Some(VehicleAllocation(rideHailingAgentLocation.vehicleId, rideHailingAgentLocation.currentLocation))))
      case None => Some(VehicleAllocationResult(None))
    }

  }

// TODO: should we use normal without break
  // use lockVehicle
  def allocateBatchRequests(allocationBatchRequest: Map[Id[RideHailingInquiry], VehicleAllocationRequest]): Map[Id[RideHailingInquiry], VehicleAllocationResult] = {

    var result = Map[Id[RideHailingInquiry], VehicleAllocationResult]()
    var alreadyUsedVehicles = collection.mutable.Set[Id[Vehicle]]()
    for ((rideHailingInquiry, vehicleAllocationRequest) <- allocationBatchRequest) {
      var vehicleAllocationResult: Option[VehicleAllocationResult] = None

      breakable {
        for ((rideHailingAgentLocation, distance) <- rideHailingManager.getClosestVehiclesWithinStandardRadius(vehicleAllocationRequest.pickUpLocation, rideHailingManager.radius)) {
          if (!alreadyUsedVehicles.contains(rideHailingAgentLocation.vehicleId)) {
            alreadyUsedVehicles.add(rideHailingAgentLocation.vehicleId)
            vehicleAllocationResult = Some(VehicleAllocationResult(Some(VehicleAllocation(rideHailingAgentLocation.vehicleId,rideHailingAgentLocation.currentLocation))))
            break
          }
        }
      }

      vehicleAllocationResult match {
        case Some(vehicleAllocationResult) => result += (rideHailingInquiry -> vehicleAllocationResult)
        case None => result += (rideHailingInquiry -> VehicleAllocationResult(None))
      }
    }
    result
  }

}

