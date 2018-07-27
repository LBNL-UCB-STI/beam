package beam.agentsim.agents.ridehail.allocationManagers

import beam.agentsim.agents.ridehail.{RideHailManager, RideHailNetworkAPI}
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
class StanfordRideHailAllocationManagerV1(
  val rideHailManager: RideHailManager,
  val rideHailNetworkApi: RideHailNetworkAPI
) extends RideHailResourceAllocationManager {
  val isBufferedRideHailAllocationMode = false

  /*
  This method is used to provide an initial vehicle allocation proposal (vehicleAllocationRequest.isInquiry==true).
  If the customer accepts the proposal, a second call will be made with (vehicleAllocationRequest.isInquiry==false),
  which initiates the ridehail agent to pickup the customer.

  This assignment can be attempted to overwritten later when the allocateVehicles is called.
   */

  override def proposeVehicleAllocation(
    vehicleAllocationRequest: VehicleAllocationRequest
  ): Option[VehicleAllocation] = {
    val rideHailAgentLocation = rideHailManager.getClosestIdleRideHailAgent(
      vehicleAllocationRequest.pickUpLocation,
      rideHailManager.radiusInMeters
    )

    rideHailAgentLocation match {
      case Some(rideHailLocation) =>
        Some(VehicleAllocation(rideHailLocation.vehicleId, rideHailLocation.currentLocation))
      case None => None
    }
  }

  /*
    This method can be used to attempt an overwrite of previous vehicle allocation proposal (only possible, if passenger not already picked up).
    This method is called periodically, e.g. every 60 seconds.
   */

  def allocateVehicles(
    allocationsDuringReservation: Vector[(VehicleAllocationRequest, Option[VehicleAllocation])]
  ): IndexedSeq[(VehicleAllocationRequest, Option[VehicleAllocation])] = {
    var result = ArrayBuffer[(VehicleAllocationRequest, Option[VehicleAllocation])]()
    val alreadyUsedVehicles = collection.mutable.Set[Id[Vehicle]]()
    for ((vehicleAllocationRequest, _) <- allocationsDuringReservation) {
      var vehicleAllocation: Option[VehicleAllocation] = None

      breakable {
        for (rideHailAgentLocation <- rideHailManager.getClosestIdleVehiclesWithinRadius(
               vehicleAllocationRequest.pickUpLocation,
               rideHailManager.radiusInMeters
             )) {
          if (!alreadyUsedVehicles.contains(rideHailAgentLocation.vehicleId)) {
            alreadyUsedVehicles.add(rideHailAgentLocation.vehicleId)
            vehicleAllocation = Some(
              VehicleAllocation(
                rideHailAgentLocation.vehicleId,
                rideHailAgentLocation.currentLocation
              )
            )
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
    if (rideHailManager.getIdleVehicles.size >= 2) {
      val iter = rideHailManager.getIdleVehicles.iterator
      val (vehicleIdA, _) = iter.next()
      val (_, vehicleLocationB) = iter.next()
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
  def apiExamples(
    vehicleAllocationRequest: VehicleAllocationRequest
  ): TrieMap[Id[Vehicle], RideHailManager.RideHailAgentLocation] = {

    // network operations
    val linkId = 5
    rideHailNetworkApi.getClosestLink(vehicleAllocationRequest.pickUpLocation)
    val links = rideHailNetworkApi.getLinks
    rideHailNetworkApi.getTravelTimeEstimate(vehicleAllocationRequest.departAt.atTime, linkId)
    rideHailNetworkApi.getFreeFlowTravelTime(linkId)
    val fromLinkIds = rideHailNetworkApi.getFromLinkIds(linkId)
    val toLinkIds = rideHailNetworkApi.getToLinkIds(linkId)
    val coord = rideHailNetworkApi.getLinkCoord(linkId)
    val fromCoord = rideHailNetworkApi.getFromNodeCoordinate(linkId)
    val toCoord = rideHailNetworkApi.getToNodeCoordinate(linkId)

    // RHM
    val rideHailAgentLocation = rideHailManager
      .getClosestIdleRideHailAgent(
        vehicleAllocationRequest.pickUpLocation,
        rideHailManager.radiusInMeters
      )
      .get
    rideHailManager.getVehicleFuelLevel(rideHailAgentLocation.vehicleId)
    rideHailManager.getClosestIdleVehiclesWithinRadius(
      vehicleAllocationRequest.pickUpLocation,
      rideHailManager.radiusInMeters
    )
    rideHailManager.getIdleVehicles
  }
}
