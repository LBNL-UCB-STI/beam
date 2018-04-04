package beam.agentsim.agents.rideHail

import beam.agentsim.agents.rideHail.RideHailingManager.{RideHailingAgentLocation, RideHailingInquiry}
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.Location
import beam.router.RoutingModel.BeamTime
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle
import org.slf4j.{Logger, LoggerFactory}

class DefaultRideHailResourceAllocationManager extends RideHailResourceAllocationManager {

  val isBufferedRideHailAllocationMode = false


  def getVehicleAllocation(vehicleAllocationRequest: VehicleAllocationRequest): Option[VehicleAllocation] = {
    None
  }

// TODO RW/Asif: how to make sure no one ever can call this?
def allocateVehiclesInBatch(allocationsDuringReservation: Map[Id[RideHailingInquiry], Option[(VehicleAllocation, RideHailingAgentLocation)]]): Map[Id[RideHailingInquiry], Option[VehicleAllocation]] = {
    DefaultRideHailResourceAllocationManager.log.error("batch processing is not implemented for DefaultRideHailResourceAllocationManager")
    ???
  }
}


object DefaultRideHailResourceAllocationManager{
  def log: Logger = LoggerFactory.getLogger(classOf[DefaultRideHailResourceAllocationManager])
}




