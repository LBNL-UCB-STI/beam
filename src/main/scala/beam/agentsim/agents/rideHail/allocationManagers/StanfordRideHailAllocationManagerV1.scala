package beam.agentsim.agents.rideHail.allocationManagers

import beam.agentsim.agents.rideHail.RideHailingManager
import beam.router.BeamRouter.Location
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

import scala.collection.concurrent.TrieMap
import scala.collection.mutable.ArrayBuffer
import scala.util.control.Breaks._

/*
TODO: check all network api, if they can use them properly

TODO: vehicleAllocationRequest.isInquiry==false => we need to make that call and allow the overwrite!

TODO: changing previous allocation should work

TODO: repositioning vehicles should be able to get duty any time

 */
class StanfordRideHailAllocationManagerV1(val rideHailingManager: RideHailingManager) extends RideHailResourceAllocationManager {
  val isBufferedRideHailAllocationMode = false


  /*
  This method is used to provide an initial vehicle allocation proposal (vehicleAllocationRequest.isInquiry==true).
  If the customer accepts the proposal, a second call will be made with (vehicleAllocationRequest.isInquiry==false),
  which initiates the ridehail agent to pickup the customer.

  This assignment can be attempted to overwritten later when the allocateVehicles is called.
   */

  override def proposeVehicleAllocation(vehicleAllocationRequest: VehicleAllocationRequest): Option[VehicleAllocation] = {
    val rideHailingAgentLocation = rideHailingManager.getClosestIdleRideHailingAgent(vehicleAllocationRequest.pickUpLocation, RideHailingManager.radiusInMeters)

    rideHailingAgentLocation match {
      case Some((rideHailingAgentLocation, distance)) => Some(VehicleAllocation(rideHailingAgentLocation.vehicleId, rideHailingAgentLocation.currentLocation))
      case None => None
    }
  }

  /*
    This method can be used to attempt an overwrite of previous vehicle allocation proposal (only possible, if passenger not already picked up).
    This method is called periodically, e.g. every 60 seconds.
   */

  def allocateVehicles(allocationsDuringReservation: Vector[(VehicleAllocationRequest, Option[VehicleAllocation])]): IndexedSeq[(VehicleAllocationRequest, Option[VehicleAllocation])] = {
    var result = ArrayBuffer[(VehicleAllocationRequest, Option[VehicleAllocation])]()
    val alreadyUsedVehicles = collection.mutable.Set[Id[Vehicle]]()
    for ((vehicleAllocationRequest, vehicleAllocation) <- allocationsDuringReservation) {
      var vehicleAllocation: Option[VehicleAllocation] = None

      breakable {
        for ((rideHailingAgentLocation, distance) <- rideHailingManager.getClosestIdleVehiclesWithinRadius(vehicleAllocationRequest.pickUpLocation, RideHailingManager.radiusInMeters)) {
          if (!alreadyUsedVehicles.contains(rideHailingAgentLocation.vehicleId)) {
            alreadyUsedVehicles.add(rideHailingAgentLocation.vehicleId)
            vehicleAllocation = Some(VehicleAllocation(rideHailingAgentLocation.vehicleId, rideHailingAgentLocation.currentLocation))
            break
          }
        }
      }

      result += ((vehicleAllocationRequest, vehicleAllocation))
    }
    result
  }


  /*
    This method is called periodically, e.g. every 60 seconds to reposition ride hailing vehicles, e.g. towards areas of higher demand
   */
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


  // TODO: allow specifying route not only dest coord
  // need capacity and number of vehicles on road to implement it

/*
  API available to implement allocation manager
 */
  def apiExamples(vehicleAllocationRequest: VehicleAllocationRequest): TrieMap[Id[Vehicle], RideHailingManager.RideHailingAgentLocation] = {

    // network operations
    val linkId = 5
    rideHailingManager.getClosestLink(vehicleAllocationRequest.pickUpLocation)
    val links = rideHailingManager.getLinks()
    rideHailingManager.getTravelTimeEstimate(vehicleAllocationRequest.departAt.atTime, linkId)
    rideHailingManager.getFreeFlowTravelTime(linkId)
    val fromLinkIds = rideHailingManager.getFromLinkIds(linkId)
    val toLinkIds = rideHailingManager.getToLinkIds(linkId)
    val coord = rideHailingManager.getLinkCoord(linkId)
    val fromCoord = rideHailingManager.getFromNodeCoordinate(linkId)
    val toCoord = rideHailingManager.getToNodeCoordinate(linkId)

    // RHM
    val (rideHailAgentLocation, distance) = rideHailingManager.getClosestIdleRideHailingAgent(vehicleAllocationRequest.pickUpLocation, RideHailingManager.radiusInMeters).get
    rideHailingManager.getVehicleFuelLevel(rideHailAgentLocation.vehicleId)
    rideHailingManager.getClosestIdleVehiclesWithinRadius(vehicleAllocationRequest.pickUpLocation, RideHailingManager.radiusInMeters)
    rideHailingManager.getIdleVehicles()

  }
}