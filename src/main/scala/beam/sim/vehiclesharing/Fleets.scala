package beam.sim.vehiclesharing

import beam.agentsim.agents.vehicles.VehicleManager
import beam.sim.config.BeamConfig
import beam.sim.config.BeamConfig.Beam.Agentsim.Agents.Vehicles.SharedFleets$Elm
import org.matsim.api.core.v01.Id

object Fleets {

<<<<<<< HEAD
  def lookup(
    config: BeamConfig.Beam.Agentsim.Agents.Vehicles.SharedFleets$Elm,
    agentSampleSizeAsFractionOfPopulation: Double
  ): FleetType = {
    val vehicleManagerId = Id.create(config.name, classOf[VehicleManager])
=======
  def lookup(config: BeamConfig.Beam.Agentsim.Agents.Vehicles.SharedFleets$Elm): FleetType = {
    val vehicleManager = Id.create(config.name, classOf[VehicleManager])
>>>>>>> develop
    val parkingFilePath = config.parkingFilePath
    config.managerType match {
      case "fixed-non-reserving-fleet-by-taz" =>
        val value: SharedFleets$Elm.FixedNonReservingFleetByTaz = config.fixed_non_reserving_fleet_by_taz.get
<<<<<<< HEAD
        FixedNonReservingFleetByTAZ(
          vehicleManagerId,
          parkingFilePath,
          value,
          agentSampleSizeAsFractionOfPopulation,
          config.reposition
        )
=======
        FixedNonReservingFleetByTAZ(vehicleManager, parkingFilePath, value, config.reposition)
>>>>>>> develop
      case "inexhaustible-reserving" =>
        val value: SharedFleets$Elm.InexhaustibleReserving = config.inexhaustible_reserving.get
        InexhaustibleReservingFleet(vehicleManager, parkingFilePath, value)
      case "fixed-non-reserving" =>
        val value: SharedFleets$Elm.FixedNonReserving = config.fixed_non_reserving.get
        FixedNonReservingFleet(vehicleManager, parkingFilePath, value)
      case _ =>
        throw new RuntimeException("Unknown fleet type")
    }
  }
}
