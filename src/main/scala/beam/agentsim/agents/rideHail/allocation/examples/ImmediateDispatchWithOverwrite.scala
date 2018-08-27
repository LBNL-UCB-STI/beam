package beam.agentsim.agents.rideHail.allocation.examples

import beam.agentsim.agents.modalbehaviors.DrivesVehicle.StopDrivingIfNoPassengerOnBoardReply
import beam.agentsim.agents.ridehail.RideHailManager
import beam.agentsim.agents.ridehail.allocation.{
  RideHailResourceAllocationManager,
  VehicleAllocation,
  VehicleAllocationRequest
}
import beam.router.BeamRouter.Location
import beam.router.RoutingModel.DiscreteTime
import beam.sim.metrics.MetricsPrinter.Print
import beam.utils.DebugLib
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person
import org.matsim.vehicles.Vehicle

import scala.collection.mutable

/*
Idea: try to buffer one request at a time.


 */
class ImmediateDispatchWithOverwrite(val rideHailManager: RideHailManager)
    extends RideHailResourceAllocationManager(rideHailManager) {

  var bufferedRideHailRequest: Option[VehicleAllocationRequest] = None
  var reservationCompleted = false
  var overwriteAttemptStarted = false

  override def proposeVehicleAllocation(
    vehicleAllocationRequest: VehicleAllocationRequest
  ): Option[VehicleAllocation] = {

    bufferedRideHailRequest match {
      case None => bufferedRideHailRequest = Some(vehicleAllocationRequest)
      case _    =>
    }

    // just go with closest request
    None
  }

  override def handleRideCancellationReply(reply: StopDrivingIfNoPassengerOnBoardReply): Unit = {

    bufferedRideHailRequests.decreaseNumberOfOpenOverwriteRequests()

    if (reply.success) {
      // overwrite first ride of day

      val firstRequestOfDay = bufferedRideHailRequest.get

      val rhl = rideHailManager
        .getClosestIdleRideHailAgent(
          firstRequestOfDay.pickUpLocation,
          rideHailManager.radiusInMeters
        )
        .get

      val request = firstRequestOfDay.request.copy(
        departAt = DiscreteTime(bufferedRideHailRequests.getTick().toInt)
      )

      rideHailManager.requestRoutesToCustomerAndDestination(
        request,
        rhl
      )

      println(
        s" new vehicle assigned:${rhl.vehicleId}, tick: ${reply.tick}"
      )

      logger.debug(
        s" new vehicle assigned:${rhl.vehicleId}, tick: ${reply.tick}"
      )

      bufferedRideHailRequests.registerVehicleAsReplacementVehicle(rhl.vehicleId)

    } else {
      println(
        s"reassignment failed"
      )
      bufferedRideHailRequests.tryClosingBufferedRideHailRequestWaive()
    }

    bufferedRideHailRequest = None
    overwriteAttemptStarted = false
    reservationCompleted = false

  }

  // TODO: define 3 state names to allow for proper transitioning

  override def updateVehicleAllocations(tick: Double, triggerId: Long): Unit = {
    // try to cancel first ride of day

    bufferedRideHailRequest match {
      case Some(bufferedRideHailRequest) if !overwriteAttemptStarted && reservationCompleted =>
        println(
          s"trying to reassign vehicle to customer:${bufferedRideHailRequest.request.customer}, tick: $tick"
        )
        logger.debug(
          s"trying to reassign vehicle to customer:${bufferedRideHailRequest.request.customer}, tick: $tick"
        )
        rideHailManager.attemptToCancelCurrentRideRequest(
          tick,
          bufferedRideHailRequest.request.requestId
        )
        logger.debug(
          s"attempt finished, tick: $tick"
        )
        bufferedRideHailRequests.increaseNumberOfOpenOverwriteRequests()
        overwriteAttemptStarted = true

      case _ =>
    }

  }

  override def reservationCompletionNotice(personId: Id[Person], vehicleId: Id[Vehicle]): Unit = {
    bufferedRideHailRequest match {
      case Some(bufferedRideHailRequest)
          if bufferedRideHailRequest.request.customer.personId == personId =>
        reservationCompleted = true
      case _ =>
    }

  }

}
