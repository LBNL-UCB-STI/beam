package beam.sim.vehiclesharing
import beam.sim.config.BeamConfig
import beam.sim.config.BeamConfig.Beam.Agentsim.Agents.Vehicles.SharedFleets$Elm
import org.matsim.api.core.v01.Id

object Fleets {

  def lookup(config: BeamConfig.Beam.Agentsim.Agents.Vehicles.SharedFleets$Elm): FleetType = {
    val vehicleManagerId = Id.create(config.name, classOf[VehicleManager])
    config.managerType match {
      case "fixed-non-reserving-fleet-by-taz" =>
        val value: SharedFleets$Elm.FixedNonReservingFleetByTaz = config.fixed_non_reserving_fleet_by_taz.get
        FixedNonReservingFleetByTAZ(vehicleManagerId, value, config.reposition)
      case "inexhaustible-reserving" =>
        val value: SharedFleets$Elm.InexhaustibleReserving = config.inexhaustible_reserving.get
        InexhaustibleReservingFleet(value)
      case "fixed-non-reserving" =>
        val value: SharedFleets$Elm.FixedNonReserving = config.fixed_non_reserving.get
        FixedNonReservingFleet(vehicleManagerId, value)
      case _ =>
        throw new RuntimeException("Unknown fleet type")
    }
  }
}
