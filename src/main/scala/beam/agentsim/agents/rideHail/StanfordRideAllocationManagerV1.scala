package beam.agentsim.agents.rideHail
import beam.agentsim.agents.rideHail.RideHailingManager.{RideHailingAgentLocation, RideHailingInquiry}
import beam.router.BeamRouter.Location
import beam.router.RoutingModel
import org.matsim.api.core.v01.{Coord, Id}

/*

 */
  class StanfordRideAllocationManagerV1(val rideHailingManager: RideHailingManager) extends RideHailResourceAllocationManager {
    val isBufferedRideHailAllocationMode = false




  /*
This method is used to provide vehicle allocation both during inquiry and reservation. The reservation may be overwritten later by allocateVehiclesInBatch
 */
  override def getVehicleAllocation(vehicleAllocationRequest: VehicleAllocationRequest): Option[VehicleAllocation] = {


      val linkId=5
      rideHailingManager.getClosestLink(vehicleAllocationRequest.pickUpLocation)
      val links=rideHailingManager.getLinks()
      rideHailingManager.getTravelTimeEstimate(vehicleAllocationRequest.departAt.atTime,linkId)
      rideHailingManager.getFreeFlowTravelTime(linkId)
      val (rideHailAgentLocation,distance)=rideHailingManager.getClosestRideHailingAgent(vehicleAllocationRequest.pickUpLocation,rideHailingManager.radius).get
      rideHailingManager.getVehicleFuelLevel(rideHailAgentLocation.vehicleId)
      rideHailingManager.getClosestVehiclesWithinStandardRadius(vehicleAllocationRequest.pickUpLocation,rideHailingManager.radius)
      rideHailingManager.getIdleVehicles()
      val fromLinkIds= rideHailingManager.getFromLinkIds(linkId)
      val toLinkIds= rideHailingManager.getToLinkIds(linkId)
      val coord = rideHailingManager.getLinkCoord(linkId)
      val fromCoord= rideHailingManager.getFromNodeCoordinate(linkId)
      val toCoord=rideHailingManager.getToNodeCoordinate(linkId)


      ???
    }

  /*
    This method can be used to attempt an overwrite of previous vehicle allocations (has no affect if vehicle already arrived).

   */
    override def allocateVehiclesInBatch(allocationsDuringReservation: Map[Id[RideHailingInquiry],Option[(VehicleAllocation,RideHailingAgentLocation)]]): Map[Id[RideHailingInquiry],Option[VehicleAllocation]] = {




      ???
    }


  }
