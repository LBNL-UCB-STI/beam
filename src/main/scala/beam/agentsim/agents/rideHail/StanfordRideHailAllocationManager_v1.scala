package beam.agentsim.agents.rideHail

import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.Location
import beam.router.RoutingModel.BeamTime

class StanfordRideHailAllocationManager_v1 extends RideHailResourceAllocationManager {
  def getVehicleAllocation( pickUpLocation: Location, departAt: BeamTime, destination: Location): Option[VehicleAllocationResult] = ???
}
