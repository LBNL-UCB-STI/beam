package beam.sim.vehicles

import beam.agentsim.agents.Population
import beam.agentsim.agents.vehicles.{BeamVehicleType, VehicleCategory}
import beam.sim.{BeamScenario, BeamServices}
import beam.utils.csv.readers
import beam.utils.scenario.VehicleInfo
import org.apache.commons.math3.distribution.UniformRealDistribution
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.households.Household

case class FromFileVehiclesAdjustment(beamScenario: BeamScenario) extends VehiclesAdjustment {

  val vehicles: Iterable[VehicleInfo] = readVehiclesFromFile();
  val vehicleTypesMap: Map[Id[BeamVehicleType], BeamVehicleType] = beamScenario.vehicleTypes.map(i => i._1 -> i._2).toMap;

  override def sampleVehicleTypesForHousehold(
    numVehicles: Int,
    vehicleCategory: VehicleCategory.VehicleCategory,
    householdIncome: Double,
    householdSize: Int,
    householdPopulation: Population,
    householdLocation: Coord,
    realDistribution: UniformRealDistribution,
    householdId: Id[Household]
  ): List[BeamVehicleType] = {
    vehicles
      .filter(x => Id.create(x.householdId, classOf[Household]).equals(householdId))
      .map(x => Id.create(x.vehicleTypeId, classOf[BeamVehicleType]))
      .map(x => vehicleTypesMap(x))
      .toList
  }

  override def sampleVehicleTypes(
    numVehicles: Int,
    vehicleCategory: VehicleCategory.VehicleCategory,
    realDistribution: UniformRealDistribution
  ): List[BeamVehicleType] = {
    List()
  }

  private def readVehiclesFromFile() = {
    readers.BeamCsvScenarioReader.readVehiclesFile(
      beamScenario.beamConfig.beam.agentsim.agents.vehicles.vehiclesFilePath
    )
  }
}