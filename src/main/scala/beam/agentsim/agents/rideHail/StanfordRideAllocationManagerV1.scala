package beam.agentsim.agents.rideHail
import beam.router.BeamRouter.Location
import beam.router.RoutingModel
import org.matsim.api.core.v01.Id


  class StanfordRideAllocationManagerV1(val rideHailingManager: RideHailingManager) extends RideHailResourceAllocationManager {

    val isBufferedRideHailAllocationMode = false

    override def getVehicleAllocation(pickUpLocation: Location, departAt: RoutingModel.BeamTime, destination: Location): Option[VehicleAllocationResult] = {
      rideHailingManager.getClosestLink(pickUpLocation)

      val links=rideHailingManager.getLinks()

      rideHailingManager.getTravelTimeEstimate(55,15)

      rideHailingManager.getFreeFlowTravelTime(15)

      val (rideHailAgentLocation,distance)=rideHailingManager.getClosestRideHailingAgent(pickUpLocation,rideHailingManager.radius).get

      rideHailingManager.getVehicleFuelLevel(rideHailAgentLocation.vehicleId)

      rideHailingManager.getClosestVehiclesWithinStandardRadius(pickUpLocation,rideHailingManager.radius)

      ???
    }

    override def allocateBatchRequests(allocationBatchRequest: Map[Id[RideHailingManager.RideHailingInquiry], VehicleAllocationRequest]): Map[Id[RideHailingManager.RideHailingInquiry], VehicleAllocationResult] = ???
  }
