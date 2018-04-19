package beam.agentsim.agents.rideHail

import beam.agentsim.agents.rideHail.RideHailingManager.{RideHailingAgentLocation, RideHailingInquiry}
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.Location
import beam.router.RoutingModel
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.vehicles.Vehicle
import scala.util.control.Breaks._

/*

 */
class StanfordRideAllocationManagerV1(val rideHailingManager: RideHailingManager) extends RideHailResourceAllocationManager {
  val isBufferedRideHailAllocationMode = false


  /*
  This method is used to provide an initial vehicle allocation proposal (vehicleAllocationRequest.isInquiry==true).
  If the customer accepts the proposal, a second call will be made with (vehicleAllocationRequest.isInquiry==false),
  which initiates the ridehail agent to pickup the customer.

  This assignment can be attempted to overwritten later when the allocateVehicles is called.
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

    }
  }

  /*
    This method can be used to attempt an overwrite of previous vehicle allocation proposal (only possible, if passenger not already picked up).
    This method is called periodically, e.g. every 60 seconds.
   */

  def allocateVehicles(allocationsDuringReservation: Vector[(VehicleAllocationRequest, Option[VehicleAllocation])]): Vector[(VehicleAllocationRequest, Option[VehicleAllocation])] = {
    var result = Vector[(VehicleAllocationRequest, Option[VehicleAllocation])]()
    var alreadyUsedVehicles = collection.mutable.Set[Id[Vehicle]]()
    for ((vehicleAllocationRequest, vehicleAllocation) <- allocationsDuringReservation) {
      var vehicleAllocation: Option[VehicleAllocation] = None

      breakable {
        for ((rideHailingAgentLocation, distance) <- rideHailingManager.getClosestVehiclesWithinRadius(vehicleAllocationRequest.pickUpLocation, rideHailingManager.radius)) {
          if (!alreadyUsedVehicles.contains(rideHailingAgentLocation.vehicleId)) {
            alreadyUsedVehicles.add(rideHailingAgentLocation.vehicleId)
            vehicleAllocation = Some(VehicleAllocation(rideHailingAgentLocation.vehicleId, rideHailingAgentLocation.currentLocation))
            break
          }
        }
      }

      result = result :+ (vehicleAllocationRequest, vehicleAllocation)
    }
    result
  }


  /*
    This method is called periodically, e.g. every 60 seconds to reposition ride hailing vehicles, e.g. towards areas of higher demand
   */
  override def repositionVehicles(tick: Double): Vector[(Id[Vehicle], SpaceTime)] = {
    if (rideHailingManager.getIdleVehicles().size >= 2) {
      val iter = rideHailingManager.getIdleVehicles().iterator
      val (vehicleIdA, vehicleLocationA) = iter.next()
      val (vehicleIdB, vehicleLocationB) = iter.next()
      Vector((vehicleIdA, vehicleLocationB.currentLocation))
    } else {
      Vector()
    }
  }
}
