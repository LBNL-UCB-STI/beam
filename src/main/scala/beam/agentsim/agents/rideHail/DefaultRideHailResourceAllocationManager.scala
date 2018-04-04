package beam.agentsim.agents.rideHail

import beam.agentsim.agents.rideHail.RideHailingManager.RideHailingInquiry
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.Location
import beam.router.RoutingModel.BeamTime
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle
import org.slf4j.{Logger, LoggerFactory}

class DefaultRideHailResourceAllocationManager(val rideHailingManager: RideHailingManager) extends RideHailResourceAllocationManager {

  val isBufferedRideHailAllocationMode = false


  def getVehicleAllocation( pickUpLocation: Location, departAt: BeamTime, destination: Location, isInquiry: Boolean): Option[VehicleAllocation] = {
    None
  }

// TODO RW/Asif: how to make sure no one ever can call this?
def allocateVehiclesInBatch(allocationBatchRequest: Map[Id[RideHailingInquiry],Option[VehicleAllocation]]): Map[Id[RideHailingInquiry],Option[VehicleAllocation]] = {
    //log.error("batch processing is not implemented for DefaultRideHailResourceAllocationManager")
    // TODO Asif: repair compilaiton error caused by above line
    ???
  }
}


object DefaultRideHailResourceAllocationManager{
  val log: Logger = LoggerFactory.getLogger(classOf[DefaultRideHailResourceAllocationManager])
}




