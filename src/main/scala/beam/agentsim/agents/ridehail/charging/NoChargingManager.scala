package beam.agentsim.agents.ridehail.charging

import beam.agentsim.agents.ridehail.RideHailManagerHelper
import beam.agentsim.agents.ridehail.charging.VehicleChargingManager.VehicleChargingManagerResult
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.sim.BeamServices
import org.matsim.api.core.v01.Id

import scala.collection.mutable

/**
  * Ridehail vehicles should not be recharged
  *
  * @param resources Map from Id[BeamVehicle] to BeamVehicle with all vehicles controlled by the ride hail manager.
  */
class NoChargingManager(beamServices: BeamServices, resources: mutable.Map[Id[BeamVehicle], BeamVehicle])
    extends VehicleChargingManager(beamServices, resources) {
  override def findStationsForVehiclesInNeedOfCharging(
    tick: Int,
    idleVehicles: collection.Map[Id[BeamVehicle], RideHailManagerHelper.RideHailAgentLocation]
  ): VehicleChargingManagerResult = {
    VehicleChargingManagerResult(Vector.empty)
  }
}
