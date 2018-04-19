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


/*
This method is used to provide an initial vehicle allocation proposal. The reservation may be overwritten later when allocateVehicles is called
 */

  override def proposeVehicleAllocation(vehicleAllocationRequest: VehicleAllocationRequest): Option[VehicleAllocation] = {
    val rideHailingAgentLocation = rideHailingManager.getClosestRideHailingAgent(vehicleAllocationRequest.pickUpLocation, rideHailingManager.radius)

    rideHailingAgentLocation match {
      case Some((rideHailingAgentLocation, distance)) => Some(VehicleAllocation(rideHailingAgentLocation.vehicleId, rideHailingAgentLocation.currentLocation))
      case None => None


/*
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
*/

    }}

  /*
    This method can be used to attempt an overwrite of previous vehicle allocation proposal (has no affect if passenger already picked up).
   */

  def allocateVehicles(allocationsDuringReservation: Vector[(VehicleAllocationRequest, Option[VehicleAllocation])]): Vector[(VehicleAllocationRequest, Option[VehicleAllocation])]={

    allocationsDuringReservation

  }


  /*
    This method is called periodically, e.g. every 5min to reposition ride hailing vehicles, e.g. towards areas of higher demand
   */
    override def repositionVehicles(tick: Double): Vector[(Id[Vehicle], SpaceTime)] = {
      if (rideHailingManager.getIdleVehicles().size>=2){
      val iter= rideHailingManager.getIdleVehicles().iterator
      val (vehicleIdA,vehicleLocationA) = iter.next()
      val (vehicleIdB,vehicleLocationB) = iter.next()
      Vector((vehicleIdA,vehicleLocationB.currentLocation))
      } else {
        Vector()
      }
    }
}
