package beam.agentsim.infrastructure.power

import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.infrastructure.power.PowerController.PhysicalBounds
import org.matsim.api.core.v01.Id

class SitePowerManager() {

  /**
    * Get required power for electrical vehicles
    *
    * @param vehicles beam vehicles
    * @return power (in joules) over planning horizon
    */
  def getPowerOverPlanningHorizon(vehicles: Map[Id[BeamVehicle], BeamVehicle]): Double =
    vehicles.view
      .filter { case (_, v) => v.beamVehicleType.isEV }
      .map { case (_, v) => v.beamVehicleType.primaryFuelCapacityInJoule - v.primaryFuelLevelInJoules }
      .sum

  /**
    * Replans horizon per electrical vehicles
    *
    * @param physicalBounds
    * @param vehicles beam vehicles
    * @return map of electrical vehicles with required amount of energy in joules
    */
  def replanHorizonAndGetChargingPlanPerVehicle(
    physicalBounds: PhysicalBounds,
    vehicles: Map[Id[BeamVehicle], BeamVehicle]
  ): Map[Id[BeamVehicle], Double] =
    vehicles.view
      .filter { case (_, v) => v.beamVehicleType.isEV }
      // TODO so far it returns the exact required energy
      .map { case (id, v) => (id, v.beamVehicleType.primaryFuelCapacityInJoule - v.primaryFuelLevelInJoules) }
      .toMap
}
