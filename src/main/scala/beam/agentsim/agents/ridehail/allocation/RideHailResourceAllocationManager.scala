package beam.agentsim.agents.ridehail.allocation

import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.Location
import beam.router.RoutingModel.BeamTime
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

trait RideHailResourceAllocationManager {

  // TODO RW make two traits, one for rideHail manager and one for buffered RideHail Manager?

  val isBufferedRideHailAllocationMode: Boolean

  // TODO: add distinguish param inquiry vs. reservation
  def proposeVehicleAllocation(
    vehicleAllocationRequest: VehicleAllocationRequest
  ): Option[VehicleAllocation]

  // add assigned and get back new

  def allocateVehicles(
    allocationsDuringReservation: Vector[(VehicleAllocationRequest, Option[VehicleAllocation])]
  ): IndexedSeq[(VehicleAllocationRequest, Option[VehicleAllocation])]

  def repositionVehicles(tick: Double): Vector[(Id[Vehicle], Location)]

}

object RideHailResourceAllocationManager {
  val DEFAULT_MANAGER = "DEFAULT_MANAGER"
  val BUFFERED_IMPL_TEMPLATE = "BUFFERED_IMPL_TEMPLATE"
  val STANFORD_V1 = "STANFORD_V1"
  val REPOSITIONING_LOW_WAITING_TIMES = "REPOSITIONING_LOW_WAITING_TIMES"
  val RANDOM_REPOSITIONING = "RANDOM_REPOSITIONING"
}

case class VehicleAllocation(vehicleId: Id[Vehicle], availableAt: SpaceTime)

case class VehicleAllocationRequest(
  pickUpLocation: Location,
  departAt: BeamTime,
  destination: Location,
  isInquiry: Boolean
)

// TODO (RW): mention to CS that cost removed from VehicleAllocationResult, as not needed to be returned (RHM default implementation calculates it already)
