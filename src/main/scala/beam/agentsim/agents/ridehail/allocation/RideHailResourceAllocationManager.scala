package beam.agentsim.agents.ridehail.allocation

import beam.agentsim.agents.modalbehaviors.DrivesVehicle.StopDrivingIfNoPassengerOnBoardReply
import beam.agentsim.agents.ridehail.{BufferedRideHailRequests, RideHailManager}
import beam.agentsim.agents.ridehail.RideHailManager.{
  BufferedRideHailRequestsTimeout,
  RideHailRequest
}
import beam.agentsim.events.SpaceTime
import beam.agentsim.scheduler.BeamAgentScheduler.ScheduleTrigger
import beam.router.BeamRouter.Location
import beam.router.RoutingModel.BeamTime
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle
import org.slf4j.{Logger, LoggerFactory}

abstract class RideHailResourceAllocationManager(private val rideHailManager: RideHailManager) {

  lazy val log: Logger = LoggerFactory.getLogger(getClass)

  val bufferedRideHailRequests: BufferedRideHailRequests = new BufferedRideHailRequests(
    rideHailManager.scheduler
  )

  def proposeVehicleAllocation(
    vehicleAllocationRequest: VehicleAllocationRequest
  ): Option[VehicleAllocation]

  def updateVehicleAllocations(
    tick: Double,
    triggerId: Long,
    rideHailManager: RideHailManager
  ): Unit = {
    bufferedRideHailRequests.newTimeout(tick, triggerId)

    updateVehicleAllocations(tick, triggerId)

    // TODO: refactor to BufferedRideHailRequests?
    val timerTrigger = BufferedRideHailRequestsTimeout(
      tick + 10 // TODO: replace with new config variable
    )
    val timerMessage = ScheduleTrigger(timerTrigger, rideHailManager.self)
    Vector(timerMessage)

    val nextMessage = Vector(timerMessage)

    bufferedRideHailRequests.addTriggerMessages(nextMessage)

    bufferedRideHailRequests.tryClosingBufferedRideHailRequestWaive()

  }

  def updateVehicleAllocations(tick: Double, triggerId: Long): Unit = {
    log.trace("default implementation updateVehicleAllocations executed")
  }

  def handleRideCancellationReply(reply: StopDrivingIfNoPassengerOnBoardReply): Unit = {
    log.trace("default implementation handleRideCancellationReply executed")
  }

  def repositionVehicles(tick: Double): Vector[(Id[Vehicle], Location)] = {
    log.trace("default implementation repositionVehicles executed")
    Vector()
  }

  def setBufferedRideHailRequests(bufferedRideHailRequests: BufferedRideHailRequests): Unit = {}

}

object RideHailResourceAllocationManager {
  val DEFAULT_MANAGER = "DEFAULT_MANAGER"
  val IMMEDIATE_DISPATCH_WITH_OVERWRITE = "IMMEDIATE_DISPATCH_WITH_OVERWRITE"
  val STANFORD_V1 = "STANFORD_V1"
  val REPOSITIONING_LOW_WAITING_TIMES = "REPOSITIONING_LOW_WAITING_TIMES"
  val RANDOM_REPOSITIONING = "RANDOM_REPOSITIONING"
  val DUMMY_DISPATCH_WITH_BUFFERING = "DUMMY_DISPATCH_WITH_BUFFERING"
}

case class VehicleAllocation(vehicleId: Id[Vehicle], availableAt: SpaceTime)

case class VehicleAllocationRequest(
  pickUpLocation: Location,
  departAt: BeamTime,
  destination: Location,
  isInquiry: Boolean,
  request: RideHailRequest
)

// TODO (RW): mention to CS that cost removed from VehicleAllocationResult, as not needed to be returned (RHM default implementation calculates it already)
