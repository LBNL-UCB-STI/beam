package beam.agentsim.agents.ridehail.charging

import beam.agentsim.agents.ridehail.{RideHailDepotParkingManager, RideHailManagerHelper}
import beam.agentsim.agents.ridehail.charging.VehicleChargingManager.VehicleChargingManagerResult
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.sim.BeamServices
import org.matsim.api.core.v01.Id

import scala.collection.mutable

/**
  * Default Charging Manager that uses the BeamVehicle.isRefuelNeeded method to decide which CAVs to recharge (human
  * drive ride hail vehicles are ignored and assumed to decide on their own) and then where they should charge based
  * on method in RideHailDepotParkingManager.
  *
  * @param resources Map from Id[BeamVehicle] to BeamVehicle with all vehicles controlled by the ride hail manager.
  * @param rideHailDepotParkingManager used to assign vehicles to a depot for charging.
  */
class DefaultChargingManager(
  beamServices: BeamServices,
  resources: mutable.Map[Id[BeamVehicle], BeamVehicle],
  rideHailDepotParkingManager: RideHailDepotParkingManager[_]
) extends VehicleChargingManager(beamServices, resources) {
  override def findStationsForVehiclesInNeedOfCharging(
    tick: Int,
    idleVehicles: collection.Map[Id[BeamVehicle], RideHailManagerHelper.RideHailAgentLocation]
  ): VehicleChargingManagerResult = {
    val idleVehicleIdsWantingToRefuelWithLocation = idleVehicles.toVector.filter {
      case (vehicleId: Id[BeamVehicle], _) => {
        resources.get(vehicleId) match {
          case Some(beamVehicle) if beamVehicle.isCAV => {
            beamVehicle.isRefuelNeeded(
              beamServices.beamScenario.beamConfig.beam.agentsim.agents.rideHail.cav.refuelRequiredThresholdInMeters,
              beamServices.beamScenario.beamConfig.beam.agentsim.agents.rideHail.cav.noRefuelThresholdInMeters
            )
          }
          case _ => false
        }
      }
    }

    val assignments = idleVehicleIdsWantingToRefuelWithLocation.map {
      case (vehicleId, rideHailAgentLocation) =>
        val beamVehicle = resources(vehicleId)
        val parkingStall = rideHailDepotParkingManager
          .findDepot(rideHailAgentLocation.getCurrentLocationUTM(tick, beamServices), beamVehicle, tick)
          .getOrElse(throw new IllegalStateException(s"no parkingStall available for $vehicleId"))
        (vehicleId, parkingStall)
    }

    VehicleChargingManagerResult(assignments)
  }
}
