package beam.agentsim.agents.ridehail.allocation

import beam.agentsim.agents.modalbehaviors.DrivesVehicle.StopDrivingIfNoPassengerOnBoardReply
import beam.agentsim.agents.ridehail.RideHailManager.RideHailRequest
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.Location
import beam.router.RoutingModel.BeamTime
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle
import org.slf4j.{Logger, LoggerFactory}

trait RideHailResourceAllocationManager {

  lazy val log: Logger = LoggerFactory.getLogger(getClass)

  def proposeVehicleAllocation(
    vehicleAllocationRequest: VehicleAllocationRequest
  ): Option[VehicleAllocation]

  def updateVehicleAllocations(tick: Double): Unit = {
    log.trace("default implementation updateVehicleAllocations executed")
  }

  def handleRideCancellationReply(reply: StopDrivingIfNoPassengerOnBoardReply): Unit = {
    log.trace("default implementation handleRideCancellationReply executed")
  }

  def repositionVehicles(tick: Double): Vector[(Id[Vehicle], Location)] = {
    log.trace("default implementation repositionVehicles executed")
    Vector()
  }

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
