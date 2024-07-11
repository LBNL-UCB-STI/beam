package beam.sim.vehicles

import beam.agentsim.agents.Population
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.VehicleCategory.VehicleCategory
import beam.sim.BeamScenario
import beam.utils.UniformRealDistributionEnhanced
import beam.utils.scenario.HouseholdId
import org.matsim.api.core.v01.{Coord, Id}

case class SingleTypeVehiclesAdjustment(beamScenario: BeamScenario, vehicleType: Option[String])
    extends VehiclesAdjustment {

  val vehicleId: Id[BeamVehicleType] = vehicleType match {
    case Some(vehType) => Id.create(vehType, classOf[BeamVehicleType])
    case None          => Id.create("Car", classOf[BeamVehicleType])
  }
  val vehicleTypesByCategory: BeamVehicleType = beamScenario.vehicleTypes.values.find(vt => vt.id == vehicleId).get

  override def sampleVehicleTypes(
    numVehicles: Int,
    vehicleCategory: VehicleCategory,
    realDistribution: UniformRealDistributionEnhanced
  ): List[BeamVehicleType] = {
    if (vehicleCategory != vehicleTypesByCategory.vehicleCategory)
      throw new NotImplementedError(vehicleCategory.toString)
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
    if (vehicleCategory != vehicleTypesByCategory.vehicleCategory)
      throw new NotImplementedError(vehicleCategory.toString)
    List.fill(numVehicles)(vehicleTypesByCategory)
  }
}
