package beam.agentsim.agents.rideHail
import beam.agentsim.agents.rideHail.RideHailingManager.{RideHailingAgentLocation, RideHailingInquiry}
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.Location
import beam.router.RoutingModel
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.vehicles.Vehicle

/*

 */
  class StanfordRideAllocationManagerV1(val rideHailingManager: RideHailingManager) extends RideHailResourceAllocationManager {
    val isBufferedRideHailAllocationMode = false


// TODO: add more comments to the trait for each method

  /*
This method is used to provide vehicle allocation during inquiry. The reservation may be overwritten later by allocateVehiclesInBatch
 */

  override def proposeVehicleAllocation(vehicleAllocationRequest: VehicleAllocationRequest): Option[VehicleAllocation] = {
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


      None
    }

  /*
    This method can be used to attempt an overwrite of previous vehicle allocations (has no affect if vehicle already arrived).

   */

    override def allocateVehicles(allocationsDuringReservation: Map[Id[RideHailingInquiry],Option[(VehicleAllocation,RideHailingAgentLocation)]]): Map[Id[RideHailingInquiry],Option[VehicleAllocation]] = {
      ???
    }

    override def repositionVehicles(tick: Double): Vector[(Id[Vehicle], SpaceTime)] = {
      val iter= rideHailingManager.getIdleVehicles().iterator
      val (vehicleIdA,vehicleLocationA) = iter.next()
      val (vehicleIdB,vehicleLocationB) = iter.next()
      Vector((vehicleIdA,vehicleLocationB.currentLocation))
    }
}
