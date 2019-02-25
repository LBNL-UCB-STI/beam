package beam.sim.vehicles
import beam.agentsim.agents.Population
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.VehicleCategory.{VehicleCategory}
import beam.sim.BeamServices
import org.matsim.api.core.v01.Coord

case class DefaultVehiclesAdjustment(beamServices: BeamServices) extends VehiclesAdjustment {
  val vehicleTypesByCategory = beamServices.vehicleTypes.values.groupBy(_.vehicleCategory)

  override def sampleVehicleTypesForHousehold(
    numVehicles: Int,
    vehicleCategory: VehicleCategory,
    householdIncome: Double,
    householdSize: Int,
    householdPopulation: Population,
    householdLocation: Coord
  ): List[BeamVehicleType] = {
    Range(0, numVehicles).map { i =>
      vehicleTypesByCategory(vehicleCategory).head
    }.toList
  }
}
