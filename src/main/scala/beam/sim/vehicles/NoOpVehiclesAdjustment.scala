package beam.sim.vehicles

import beam.agentsim.agents.Population
import beam.agentsim.agents.vehicles.VehicleCategory.VehicleCategory
import beam.agentsim.agents.vehicles.{BeamVehicleType, VehicleCategory}
import beam.sim.BeamScenario
import org.apache.commons.math3.distribution.UniformRealDistribution
import org.matsim.api.core.v01.Coord

case class NoOpVehiclesAdjustment(beamScenario: BeamScenario) extends VehiclesAdjustment  {
  override def sampleVehicleTypesForHousehold(
                                               numVehicles: Int,
                                               vehicleCategory: VehicleCategory,
                                               householdIncome: Double,
                                               householdSize: Int,
                                               householdPopulation: Population,
                                               householdLocation: Coord,
                                               realDistribution: UniformRealDistribution
                                             ): List[BeamVehicleType] = {
    List()
  }

  override def sampleVehicleTypes(
                                   numVehicles: Int,
                                   vehicleCategory: VehicleCategory,
                                   realDistribution: UniformRealDistribution
                                 ): List[BeamVehicleType] = {
    List()
  }
}
