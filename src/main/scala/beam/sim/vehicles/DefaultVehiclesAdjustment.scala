package beam.sim.vehicles

import beam.agentsim.agents.Population
import beam.agentsim.agents.vehicles.VehicleCategory.VehicleCategory
import beam.agentsim.agents.vehicles.{BeamVehicleType, VehicleCategory}
import beam.sim.{BeamScenario, BeamServices}
import beam.utils.UniformRealDistributionEnhanced
import beam.utils.scenario.HouseholdId
import org.matsim.api.core.v01.{Coord, Id}

case class DefaultVehiclesAdjustment(beamServices: BeamServices, beamScenario: BeamScenario)
    extends VehiclesAdjustment {
  val carId: Id[BeamVehicleType] = Id.create("Car", classOf[BeamVehicleType])
  val vehicleTypesByCategory: BeamVehicleType = beamScenario.vehicleTypes.values.find(vt => vt.id == carId).get

  override def sampleVehicleTypes(
    numVehicles: Int,
    vehicleCategory: VehicleCategory,
    realDistribution: UniformRealDistributionEnhanced
  ): List[BeamVehicleType] = {
    if (vehicleCategory != VehicleCategory.Car) throw new NotImplementedError(vehicleCategory.toString)
    List.fill(numVehicles)(vehicleTypesByCategory)
  }

  override def sampleVehicleTypesForHousehold(
    numVehicles: Int,
    vehicleCategory: VehicleCategory,
    householdIncome: Double,
    householdSize: Int,
    householdPopulation: Population,
    householdLocation: Coord,
    realDistribution: UniformRealDistributionEnhanced,
    householdId: Option[HouseholdId]
  ): List[BeamVehicleType] = {
    if (vehicleCategory != VehicleCategory.Car) throw new NotImplementedError(vehicleCategory.toString)
    List.fill(numVehicles)(vehicleTypesByCategory)
  }
}
