package beam.api.agentsim.agents.ridehail.charging

import beam.agentsim.agents.ridehail.RideHailDepotParkingManager
import beam.agentsim.agents.ridehail.charging.{
  DefaultChargingManager,
  NoChargingManager,
  VehicleChargingManager,
  VehicleChargingManagerRegistry
}
import beam.agentsim.agents.ridehail.kpis.RealTimeKpis
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.sim.BeamServices
import org.matsim.api.core.v01.Id

import scala.collection.mutable
import scala.util.Try

/**
  * API defining [[ChargingManagerFactory]] and its default implementation ([[DefaultChargingManagerFactory]]), which
  * allows to instantiate from a variety of vehicle charging managers ([[VehicleChargingManager]]) currently available in BEAM. In order to
  * add new custom vehicle charging managers, a new [[ChargingManagerFactory]] can be implemented similar to [[DefaultChargingManagerFactory]] and
  * [[DefaultChargingManagerFactory]] can be used as a delegate to access the existing vehicle charging managers.
  */
trait ChargingManagerFactory {

  def create(
    beamServices: BeamServices,
    resources: mutable.Map[Id[BeamVehicle], BeamVehicle],
    rideHailDepotParkingManager: RideHailDepotParkingManager[_],
    realTimeKpis: RealTimeKpis,
    chargingManagerName: String
  ): Try[VehicleChargingManager]

}

/**
  * Default implementation of [[ChargingManagerFactory]].
  */
class DefaultChargingManagerFactory extends ChargingManagerFactory {
  override def create(
    beamServices: BeamServices,
    resources: mutable.Map[Id[BeamVehicle], BeamVehicle],
    rideHailDepotParkingManager: RideHailDepotParkingManager[_],
    realTimeKpis: RealTimeKpis,
    chargingManagerName: String
  ): Try[VehicleChargingManager] = {
    Try {
      VehicleChargingManagerRegistry.withName(
        chargingManagerName
      ) match {
        case VehicleChargingManagerRegistry.DefaultVehicleChargingManager =>
          new DefaultChargingManager(beamServices, resources, rideHailDepotParkingManager)
        case VehicleChargingManagerRegistry.NoChargingManager =>
          new NoChargingManager(beamServices, resources)
        case x =>
          throw new IllegalStateException(s"There is no implementation for `$x`")
      }
    }
  }

}
