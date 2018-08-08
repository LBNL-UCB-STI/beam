package beam.agentsim.agents.ridehail.allocation

import beam.agentsim
import beam.agentsim.agents.modalbehaviors.DrivesVehicle.StopDrivingIfNoPassengerOnBoardReply
import beam.agentsim.agents.ridehail.{RideHailManager, TNCIterationsStatsCollector}
import beam.agentsim.agents.ridehail.RideHailManager.RideHailAgentLocation
import beam.router.BeamRouter.Location
import beam.router.RoutingModel.DiscreteTime
import beam.utils.DebugLib
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle
import org.slf4j.LoggerFactory

import scala.collection.mutable

class RideHailAllocationManagerBufferedImplTemplate(val rideHailManager: RideHailManager)
    extends RideHailResourceAllocationManager
    with HandlesBufferedRequests
    with HandlesDispatching
    with HandlesRedistribution {

  var bufferedRideHailRequests = new mutable.Queue[VehicleAllocationRequest]

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

  def handleRideCancellationReply(reply: StopDrivingIfNoPassengerOnBoardReply): Unit = {

    rideHailManager.bufferedRideHailRequests.decreaseNumberOfOpenOverwriteRequests()

    if (reply.success) {
      val firstRequestOfDay = bufferedRideHailRequests.head

      val rhl = rideHailManager
        .getClosestIdleRideHailAgent(
          firstRequestOfDay.pickUpLocation,
          rideHailManager.radiusInMeters
        )
        .get

      val request = firstRequestOfDay.request.copy(
        departAt = DiscreteTime(rideHailManager.bufferedRideHailRequests.tick.toInt)
      )

      rideHailManager.requestRoutesToCustomerAndDestination(
        request,
        rhl
      )

      println(
        s" new vehicle assigned:${rhl.vehicleId}, tick: ${reply.tick}"
      )

      rideHailManager.bufferedRideHailRequests.registerVehicleAsReplacementVehicle(rhl.vehicleId)

    } else {
      firstRidehailRequestDuringDay = true
      bufferedRideHailRequests = new mutable.Queue[VehicleAllocationRequest]
      rideHailManager.bufferedRideHailRequests.tryClosingBufferedRideHailRequestWaive()
    }

    DebugLib.emptyFunctionForSettingBreakPoint()

    // CONTINUE HERE ###########
    // failed or successful

  }

  override def updateVehicleAllocations(tick: Double): Unit = {

    if (firstRidehailRequestDuringDay && bufferedRideHailRequests.size > 0) {
      // TODO: cancel the ride
      val firstRequestOfDay = bufferedRideHailRequests.head

      println(
        s" trying to rassign vehicle to customer:${firstRequestOfDay.request.customer}, tick: $tick"
      )
      rideHailManager.attemptToCancelCurrentRideRequest(tick, firstRequestOfDay.request.requestId) // CONTINUE HERE

      // TODO: ask vehicle, if customer already picked up (can't rely on tick, as RHM tick might be in same window as driver pickup).
      //  -> make method custom
      //
      // , if not, cancel it (go to idle state properly) - make new request type for this?
      // let us lock the other ride and unlock if already picked up, otherwise dispatch it to customer

      rideHailManager.bufferedRideHailRequests.setNumberOfOverwriteRequests(1)

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

    Vector()

  }
}
