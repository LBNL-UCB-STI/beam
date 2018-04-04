package beam.agentsim.agents.rideHail

import beam.agentsim.agents.rideHail.RideHailingManager.{RideHailingAgentLocation, RideHailingInquiry}
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.Location
import beam.router.RoutingModel.BeamTime
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

trait RideHailResourceAllocationManager {

  // TODO RW make two traits, one for rideHail manager and one for buffered RideHail Manager?


  val isBufferedRideHailAllocationMode: Boolean

  // TODO Asif: change parameters to case class VehicleAllocationRequest

  // TODO: add distinguish param inquiry vs. reservation
  def getVehicleAllocation(pickUpLocation: Location, departAt: BeamTime, destination: Location, isInquiry: Boolean): Option[VehicleAllocation]

  // add assigned and get back new

  def allocateVehiclesInBatch(allocationsDuringReservation: Map[Id[RideHailingInquiry], Option[(VehicleAllocation, RideHailingAgentLocation)]]): Map[Id[RideHailingInquiry], Option[VehicleAllocation]]

}
object RideHailResourceAllocationManager{
  val DEFAULT_MANAGER="DefaultRideHailResourceAllocationManager"
  val BUFFERED_IMPL_TEMPLATE ="RideHailAllocationManagerBufferedImplTemplate"
  val STANFORD_V1 ="StanfordRideAllocationManagerV1"
}

case class VehicleAllocation(vehicleId: Id[Vehicle],availableAt: SpaceTime)

case class VehicleAllocationRequest(pickUpLocation: Location, departAt: BeamTime, destination: Location)

// TODO (RW): mention to CS that cost removed from VehicleAllocationResult, as not needed to be returned (RHM default implementation calculates it already)