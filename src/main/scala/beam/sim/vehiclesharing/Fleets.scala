package beam.sim.vehiclesharing

import beam.agentsim.agents.vehicles.VehicleManager
import beam.sim.config.BeamConfig
import beam.sim.config.BeamConfig.Beam.Agentsim.Agents.Vehicles.SharedFleets$Elm

object Fleets {

  def lookup(config: BeamConfig.Beam.Agentsim.Agents.Vehicles.SharedFleets$Elm): FleetType = {
    val vehicleManager = VehicleManager.createOrGetReservedFor(config.name, VehicleManager.TypeEnum.Shared)
    val parkingFilePath = config.parkingFilePath
    config.managerType match {
      case "fixed-non-reserving-fleet-by-taz" =>
        val value: SharedFleets$Elm.FixedNonReservingFleetByTaz = config.fixed_non_reserving_fleet_by_taz.get
        FixedNonReservingFleetByTAZ(vehicleManager.managerId, parkingFilePath, value, config.reposition)
      case "inexhaustible-reserving" =>
        val value: SharedFleets$Elm.InexhaustibleReserving = config.inexhaustible_reserving.get
        InexhaustibleReservingFleet(vehicleManager.managerId, parkingFilePath, value)
      case "fixed-non-reserving" =>
        val value: SharedFleets$Elm.FixedNonReserving = config.fixed_non_reserving.get
        FixedNonReservingFleet(vehicleManager.managerId, parkingFilePath, value)
      case _ =>
        throw new RuntimeException("Unknown fleet type")
    }
  }
}
