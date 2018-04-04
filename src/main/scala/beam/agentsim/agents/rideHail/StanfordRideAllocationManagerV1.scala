package beam.agentsim.agents.rideHail
import beam.router.BeamRouter.Location
import beam.router.RoutingModel
import org.matsim.api.core.v01.{Coord, Id}


  class StanfordRideAllocationManagerV1(val rideHailingManager: RideHailingManager) extends RideHailResourceAllocationManager {

    val isBufferedRideHailAllocationMode = false

    override def getVehicleAllocation(pickUpLocation: Location, departAt: RoutingModel.BeamTime, destination: Location, isInquiry: Boolean): Option[VehicleAllocationResult] = {
      val linkId=5
      rideHailingManager.getClosestLink(pickUpLocation)
      val links=rideHailingManager.getLinks()
      rideHailingManager.getTravelTimeEstimate(departAt.atTime,linkId)
      rideHailingManager.getFreeFlowTravelTime(linkId)
      val (rideHailAgentLocation,distance)=rideHailingManager.getClosestRideHailingAgent(pickUpLocation,rideHailingManager.radius).get
      rideHailingManager.getVehicleFuelLevel(rideHailAgentLocation.vehicleId)
      rideHailingManager.getClosestVehiclesWithinStandardRadius(pickUpLocation,rideHailingManager.radius)
      rideHailingManager.getIdleVehicles()
      val fromLinkIds= rideHailingManager.getFromLinkIds(linkId)
      val toLinkIds= rideHailingManager.getToLinkIds(linkId)
      val coord = rideHailingManager.getLinkCoord(linkId)
      val fromCoord= rideHailingManager.getFromNodeCoordinate(linkId)
      val toCoord=rideHailingManager.getToNodeCoordinate(linkId)

      ???
    }

    override def allocateVehiclesInBatch(allocationBatchRequest: Map[Id[RideHailingManager.RideHailingInquiry], VehicleAllocationRequest]): Map[Id[RideHailingManager.RideHailingInquiry], VehicleAllocationResult] = ???
  }
