package beam.agentsim.agents.ridehail.allocation

import beam.agentsim
import beam.agentsim.agents.ridehail.RideHailManager
import beam.agentsim.agents.ridehail.RideHailManager.RideHailAgentLocation
import beam.router.BeamRouter.Location
import beam.utils.DebugLib
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

import scala.collection.mutable

class RideHailAllocationManagerBufferedImplTemplate(val rideHailManager: RideHailManager)
    extends RideHailResourceAllocationManager
    with HandelsBufferedRequests {

  val bufferedRideHailRequests = new mutable.Queue[VehicleAllocationRequest]

  def proposeVehicleAllocation(
    vehicleAllocationRequest: VehicleAllocationRequest
  ): Option[VehicleAllocation] = {

    if (!vehicleAllocationRequest.isInquiry) {
      bufferedRideHailRequests += vehicleAllocationRequest
    }

    // just go with closest request
    None
  }

  var firstRidehailRequestDuringDay = true

  override def updateVehicleAllocations(tick: Double): Unit = {

    if (firstRidehailRequestDuringDay && bufferedRideHailRequests.size > 0) {
      // TODO: cancel the ride
      val firstRequestOfDay = bufferedRideHailRequests.head

      firstRidehailRequestDuringDay = false
    }
    // uncomment again after basic things function:

    /*

    for (vehicleAllocationRequest <- bufferedRideHailRequests) {

      rideHailManager.cleanCurrentPickupAssignment(
        vehicleAllocationRequest.request)

      val rideHailLocationOpt = rideHailManager.getClosestIdleRideHailAgent(
        vehicleAllocationRequest.pickUpLocation,
        rideHailManager.radiusInMeters
      )

      rideHailLocationOpt match {
        case Some(rhLocation) =>
          rideHailManager.requestRoutesToCustomerAndDestination(
            vehicleAllocationRequest.request,
            rhLocation)
          true
        case None =>
          false
      }

      // TODO: allow to stop currently assigned vehicles

      // TODO: push down for clean api (just provide which new allocations to use -

    }

    bufferedRideHailRequests.clear()
   */
  }

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
}
