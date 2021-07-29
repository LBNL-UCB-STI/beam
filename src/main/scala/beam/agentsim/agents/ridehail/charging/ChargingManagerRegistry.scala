package beam.agentsim.agents.ridehail.charging

import beam.agentsim.agents.ridehail.charging.ChargingManagerRegistry.VehicleChargingManagerName
import beam.utils.Registry

object ChargingManagerRegistry {
  trait VehicleChargingManagerName
}

/**
  * List of default vehicle charging managers.
  */
object VehicleChargingManagerRegistry extends Registry[VehicleChargingManagerName] {

  case object NoChargingManager extends VehicleChargingManagerName

  case object DefaultVehicleChargingManager extends VehicleChargingManagerName

  override val entries: Set[VehicleChargingManagerName] = Set(NoChargingManager, DefaultVehicleChargingManager)
}
